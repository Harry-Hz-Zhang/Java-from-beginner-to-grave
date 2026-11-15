package com.harry.lab.process;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * 演示一：调用外部命令并安全地拿回结果，对应生产的 ProcessDockerCommandRunner。
 *
 * <p>【这是什么】把 ProcessBuilder 的四个坑一次补全：双流并发读、字节上限、
 * 无条件清理、错误分类。生产的每一条 docker 命令都走这一条路。
 *
 * <p>【第一，绝不合并 stderr。生产全仓没有 redirectErrorStream】
 * 合并之后你无法区分「docker 正常打印的警告」和「命令真的失败了」，
 * 也无法只把关键错误码抽出来判断可恢复性。生产的做法是开两个虚拟线程
 * 分别 drain stdout 和 stderr（ProcessDockerCommandRunner:34-37），
 * 而且**永远**这么做，不是「需要区分时才这么做」。
 *
 * <p>【第二，为什么必须并发读，先 waitFor 再读会死锁】
 * 子进程的 stdout/stderr 是两根容量有限的管道（Linux 上常见 64KB）。
 * 写满之后子进程的 write 调用就阻塞了，它永远退不出；父进程在 waitFor 里
 * 等它退出，于是两边互等。只要输出超过管道容量就必然发生，
 * 小输出时完全看不出来——这类 bug 只在上线后的大日志里炸。
 *
 * <p>【第三，为什么必须有字节上限】并发读解决了死锁，但如果命令输出
 * 几十 MB（docker build、docker logs 都可能），全部塞进内存就是 OOM。
 * 生产的做法是读到上限就继续读但不再保存，并置 truncated 标记
 * （:19-20、:84-86）；调用方看到 truncated 就知道结果不完整，
 * 该抛 PROTOCOL_ERROR 而不是拿半截 JSON 去解析。
 *
 * <p>【第四，finally 里的清理必须无条件执行】超时、异常、正常返回都要走。
 * 不关流会泄漏文件描述符，跑上千次 docker 命令之后进程就会报
 * "Too many open files"。进程还活着就 destroyForcibly，避免留下孤儿。
 *
 * <p>【第五，为什么要主动关 stdin】docker create、container stop 这类命令
 * 会等 stdin 读到 EOF 才继续（:39）。父进程不关，它就一直等，
 * 白等到超时，你还以为是 docker 慢。
 *
 * <p>【第六，错误要分类，不能一律 IllegalStateException】
 * 生产的 Code 有 TIMEOUT / DOCKER_UNAVAILABLE / CONFLICT / NOT_FOUND，
 * 上层据此决定「重试、报错、还是当作已完成」。尤其是 No such container：
 * 它不是 Docker 挂了，而是目标容器已经不存在（DockerCliContainerGateway:288-293）。
 * 混成 DOCKER_UNAVAILABLE 会让「容器已被删」被误判成 Docker 不可用，
 * 整个 runner 停止工作。
 *
 * <p>【生产在哪用】ProcessDockerCommandRunner 管执行，
 * DockerCliContainerGateway:410-425 管优雅终止。
 */
@Component
public class CommandRunner {

    private static final int STDOUT_LIMIT = 1024 * 1024;
    private static final int STDERR_LIMIT = 16 * 1024;

    public record Result(int exitCode, String stdout, String stderr, boolean truncated) {}

    public enum Code { TIMEOUT, DOCKER_UNAVAILABLE, CONFLICT, NOT_FOUND }

    public static final class CommandException extends RuntimeException {
        private final Code code;

        CommandException(Code code, String message) {
            super(message);
            this.code = code;
        }

        public Code code() {
            return code;
        }
    }

    public Result run(List<String> command, Duration timeout) throws Exception {
        var process = new ProcessBuilder(command).start();
        var stdout = new Capture(process.getInputStream(), STDOUT_LIMIT);
        var stderr = new Capture(process.getErrorStream(), STDERR_LIMIT);
        Thread.startVirtualThread(stdout);
        Thread.startVirtualThread(stderr);
        try {
            process.getOutputStream().close();
            if (!process.waitFor(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS)) {
                throw new CommandException(Code.TIMEOUT, "命令超时 " + timeout.toSeconds() + " 秒: " + command);
            }
            if (!stdout.await() || !stderr.await()) {
                throw new CommandException(Code.DOCKER_UNAVAILABLE, "命令已退出但输出流未关闭: " + command);
            }
            var result = new Result(process.exitValue(), stdout.text(), stderr.text(), stdout.truncated);
            if (result.exitCode() != 0) throw classify(result);
            return result;
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            closeQuietly(process.getOutputStream());
            closeQuietly(process.getInputStream());
            closeQuietly(process.getErrorStream());
        }
    }

    public Process start(List<String> command) throws IOException {
        return new ProcessBuilder(command).start();
    }

    public String terminate(Process process) throws InterruptedException {
        long started = System.nanoTime();
        var steps = new StringBuilder("waitFor(2s)→");
        if (process.waitFor(2, TimeUnit.SECONDS)) {
            steps.append("自然结束");
        } else {
            process.destroy();
            steps.append("destroy→");
            if (process.waitFor(1, TimeUnit.SECONDS)) {
                steps.append("1 秒内退出");
            } else {
                process.destroyForcibly();
                steps.append("destroyForcibly→");
                steps.append(process.waitFor(2, TimeUnit.SECONDS) ? "退出" : "仍存活");
            }
        }
        closeQuietly(process.getOutputStream());
        closeQuietly(process.getInputStream());
        closeQuietly(process.getErrorStream());
        return steps + "，退出码 " + process.exitValue() + "，耗时 "
                + (System.nanoTime() - started) / 1_000_000 + " 毫秒";
    }

    static boolean isMissing(String stderr) {
        return stderr.contains("No such container:") || stderr.contains("No such object:");
    }

    private static CommandException classify(Result result) {
        var error = result.stderr().strip();
        if (isMissing(error)) return new CommandException(Code.NOT_FOUND, "目标容器已不存在: " + error);
        var conflict = error.contains("Conflict.") || error.contains("is running");
        return new CommandException(conflict ? Code.CONFLICT : Code.DOCKER_UNAVAILABLE,
                "命令失败，退出码 " + result.exitCode() + ": " + error);
    }

    private static void closeQuietly(AutoCloseable stream) {
        try { stream.close(); } catch (Exception ignored) { }
    }

    private static final class Capture implements Runnable {
        private final InputStream input;
        private final int limit;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CountDownLatch done = new CountDownLatch(1);
        private boolean truncated;

        Capture(InputStream input, int limit) {
            this.input = input;
            this.limit = limit;
        }

        @Override
        public void run() {
            var buffer = new byte[8192];
            try {
                int count;
                while ((count = input.read(buffer)) != -1) {
                    int keep = Math.min(count, limit - bytes.size());
                    if (keep > 0) bytes.write(buffer, 0, keep);
                    if (keep < count) truncated = true;
                }
            } catch (IOException ignored) {
                // 读失败（通常是进程被杀导致管道断开）。这里只保证 countDown 让等待方继续，
                // 生产的做法是记录 failure 并抛 DOCKER_UNAVAILABLE，本 demo 从简。
            } finally {
                done.countDown();
            }
        }

        boolean await() throws InterruptedException {
            return done.await(2, TimeUnit.SECONDS);
        }

        String text() {
            return bytes.toString(StandardCharsets.UTF_8);
        }
    }
}
