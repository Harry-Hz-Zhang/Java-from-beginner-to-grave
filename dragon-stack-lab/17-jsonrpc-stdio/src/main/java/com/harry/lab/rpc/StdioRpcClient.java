package com.harry.lab.rpc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 演示二：父进程一侧的 JSON-RPC over stdio 连接，对应生产的 JsonLineRpcConnection。
 *
 * <p>【这是什么】一条连接上的四个线程角色合在一个类里：写帧、读帧、读 stderr、
 * 以及超时计时器。对外只暴露 call（同步等待）和 close。
 *
 * <p>【第一，pending 表 + 每请求超时】一条连接上可能同时有多个请求在飞，
 * 响应的顺序不保证和请求一致，必须靠 id 对应。id 用 "req_" + UUID，
 * 避免不同进程生成重复 id。超时用 ScheduledThreadPoolExecutor(1) 这个
 * 独立的计时线程池（生产 :69-74），而不是 orTimeout——因为超时那一刻
 * 要做的动作不止「让 future 失败」，还要把 pending 条目摘掉、释放名额。
 *
 * <p>【第二，超时只放弃本地关联】生产 :110-111 的注释写得很直白：
 * RPC 协议没有「取消对方操作」的方法。本地超时之后，对方的工作仍在跑，
 * 之后还可能写回一个响应。那个响应会因为 pending 里已经没有这个 id 而被忽略，
 * 这是正常的，不是 bug。
 *
 * <p>【第三，错误帧必须解析】服务端对未知方法回 -32601。如果客户端只看
 * result 字段，收到 error 帧就会 NPE。生产 :231-246 的做法是取出
 * error.code / message / data，包成带 code 的异常抛给调用方。
 *
 * <p>【第四，通知与服务端反向请求】没有 id 的行是通知，交给回调；
 * 有 id 但不是自己发出的 id，说明是服务端主动发来的请求，
 * 必须回一个 -32601，而不是静默丢弃（生产 :211-222）。
 *
 * <p>【第五，按字节扫换行，不按 BufferedReader.readLine】readLine 没有上限，
 * 对端发一个超长行就能把内存吃光；而且它会把「末尾没有换行的半帧」
 * 也当成完整一帧返回。生产 :165-183 是逐字节扫 '\n'，超过上限抛
 * PROTOCOL_ERROR，EOF 时剩下的半帧直接丢弃。
 *
 * <p>【第六，stderr 必须读，但绝不打印】不读的话子进程写满 stderr 管道会阻塞；
 * 读了之后只保留尾部 8KB 在内存里供诊断，因为里面可能有凭证
 * （生产 :39、:249-265）。这一点和「转发到日志」相反。
 *
 * <p>【第七，关闭是单向的】先让所有 pending 以 TRANSPORT_CLOSED 失败，
 * 停掉计时器，再 destroy → waitFor(1s) → destroyForcibly，最后按顺序关流。
 * 生产没有自动重连，断开后只能显式 restart（ContainerApp:130-132）。
 */
public final class StdioRpcClient implements AutoCloseable {

    private static final int MAX_FRAME_BYTES = 1024 * 1024;
    private static final int STDERR_TAIL_BYTES = 8 * 1024;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public static final class RpcException extends RuntimeException {
        private final int code;

        RpcException(int code, String message) {
            super(message);
            this.code = code;
        }

        public int code() {
            return code;
        }
    }

    private static final class Pending {
        private final CompletableFuture<Object> future = new CompletableFuture<>();
        private final AtomicBoolean done = new AtomicBoolean();
        private volatile ScheduledFuture<?> timer;
    }

    private final Process process;
    private final OutputStream toServer;
    private final InputStream fromServer;
    private final Consumer<String> notifications;
    private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();
    private final Semaphore slots = new Semaphore(16);
    private final ScheduledThreadPoolExecutor timers;
    private final byte[] stderrTail = new byte[STDERR_TAIL_BYTES];
    private final Object writeLock = new Object();
    private final AtomicBoolean open = new AtomicBoolean(true);
    private final Thread reader;
    private final Thread stderrReader;
    private int stderrPosition;
    private int stderrSize;

    public StdioRpcClient(Class<?> serverMain, Consumer<String> notifications) throws IOException {
        this.notifications = notifications;
        this.timers = new ScheduledThreadPoolExecutor(1,
                Thread.ofPlatform().daemon().name("demo-rpc-timeout-", 0).factory());
        timers.setRemoveOnCancelPolicy(true);
        var java = ProcessHandle.current().info().command().orElse("java");
        process = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                serverMain.getName()).start();
        toServer = process.getOutputStream();
        fromServer = process.getInputStream();
        reader = Thread.ofVirtual().name("demo-rpc-stdout").start(this::readStdout);
        stderrReader = Thread.ofVirtual().name("demo-rpc-stderr").start(this::readStderr);
    }

    public Object call(String method, Map<String, Object> params, Duration timeout)
            throws InterruptedException {
        if (!open.get()) throw new RpcException(-32000, "TRANSPORT_CLOSED: connection is closed");
        if (!slots.tryAcquire()) throw new RpcException(-32000, "CONFLICT: pending limit reached");
        var id = "req_" + UUID.randomUUID().toString().replace("-", "");
        var entry = new Pending();
        pending.put(id, entry);
        entry.timer = timers.schedule(() -> settle(id, entry, null,
                        new RpcException(-32000, "TIMEOUT: request timed out; peer work may still be running")),
                Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
        try {
            var frame = new LinkedHashMap<String, Object>();
            frame.put("jsonrpc", "2.0");
            frame.put("id", id);
            frame.put("method", method);
            if (params != null) frame.put("params", params);
            write(frame);
        } catch (IOException failure) {
            terminate(new RpcException(-32000, "TRANSPORT_CLOSED: cannot write request"));
        }
        try {
            return entry.future.get();
        } catch (ExecutionException failure) {
            throw failure.getCause() instanceof RpcException rpc
                    ? rpc : new RpcException(-32000, "RPC failed");
        }
    }

    public String diagnostics() {
        synchronized (stderrTail) {
            int start = (stderrPosition - stderrSize + stderrTail.length) % stderrTail.length;
            return new String(stderrTail, start, stderrSize, StandardCharsets.UTF_8);
        }
    }

    @Override
    public void close() {
        terminate(new RpcException(-32000, "TRANSPORT_CLOSED: connection closed by caller"));
    }

    private void write(Object frame) throws IOException {
        var bytes = JSON.writeValueAsBytes(frame);
        if (bytes.length > MAX_FRAME_BYTES) {
            throw new RpcException(-32700, "PROTOCOL_ERROR: frame exceeds the byte limit");
        }
        synchronized (writeLock) {
            toServer.write(bytes);
            toServer.write('\n');
            toServer.flush();
        }
    }

    private void readStdout() {
        var line = new ByteArrayOutputStream(2048);
        var buffer = new byte[8192];
        try {
            int count;
            while (open.get() && (count = fromServer.read(buffer)) != -1) {
                for (int i = 0; i < count && open.get(); i++) {
                    if (buffer[i] == '\n') {
                        handleLine(line.toString(StandardCharsets.UTF_8));
                        line.reset();
                    } else if (line.size() >= MAX_FRAME_BYTES) {
                        throw new RpcException(-32700, "PROTOCOL_ERROR: stdout frame exceeds the byte limit");
                    } else {
                        line.write(buffer[i]);
                    }
                }
            }
            terminate(new RpcException(-32000, "TRANSPORT_CLOSED: stdout closed"));
        } catch (IOException failure) {
            terminate(new RpcException(-32000, "TRANSPORT_CLOSED: stdout read failed"));
        }
        // 末尾没有换行的半帧不处理：它不是一帧。
    }

    private void handleLine(String line) {
        if (line.isBlank()) return;
        final JsonNode frame;
        try {
            frame = JSON.readTree(line);
        } catch (RuntimeException malformed) {
            return;
        }
        if (frame == null) return;
        var method = frame.get("method");
        var id = frame.get("id");
        if (method != null) {
            if (id != null && !id.isNull()) {
                System.out.println("[client] 收到服务端反向请求 " + method.asText() + "，回复 -32601");
                try {
                    write(Map.of("jsonrpc", "2.0", "id", id.asText(),
                            "error", Map.of("code", -32601, "message", "Server-to-client requests are not supported")));
                } catch (IOException failure) {
                    terminate(new RpcException(-32000, "TRANSPORT_CLOSED: cannot reply"));
                }
            } else {
                notifications.accept(method.asText() + " " + frame.path("params"));
            }
            return;
        }
        if (id == null || id.isNull()) return;
        var entry = pending.get(id.asText());
        if (entry == null) return;
        if (frame.has("error")) {
            var error = frame.get("error");
            settle(id.asText(), entry, null, new RpcException(
                    error.path("code").asInt(-32000), error.path("message").asText("JSON-RPC error")));
        } else {
            settle(id.asText(), entry, value(frame.get("result")), null);
        }
    }

    private static Object value(JsonNode node) {
        if (node == null) return null;
        return node.isString() ? node.asString() : node.toString();
    }

    private void readStderr() {
        var buffer = new byte[2048];
        try {
            int count;
            while (open.get() && (count = process.getErrorStream().read(buffer)) != -1) {
                synchronized (stderrTail) {
                    for (int i = 0; i < count; i++) {
                        stderrTail[stderrPosition] = buffer[i];
                        stderrPosition = (stderrPosition + 1) % stderrTail.length;
                        stderrSize = Math.min(stderrSize + 1, stderrTail.length);
                    }
                }
            }
        } catch (IOException ignored) {
            // 内容可能含凭证，只留在内存里，绝不写日志。
        }
    }

    private void settle(String id, Pending entry, Object result, RpcException failure) {
        if (!entry.done.compareAndSet(false, true)) return;
        pending.remove(id);
        if (entry.timer != null) entry.timer.cancel(false);
        slots.release();
        if (failure != null) entry.future.completeExceptionally(failure);
        else entry.future.complete(result);
    }

    private void terminate(RpcException failure) {
        if (!open.compareAndSet(true, false)) return;
        pending.forEach((id, entry) -> settle(id, entry, null, failure));
        timers.shutdownNow();
        reader.interrupt();
        stderrReader.interrupt();
        process.destroy();
        try {
            if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        closeQuietly(toServer);
        closeQuietly(fromServer);
        closeQuietly(process.getErrorStream());
    }

    private static void closeQuietly(AutoCloseable stream) {
        try {
            stream.close();
        } catch (Exception ignored) {
        }
    }
}
