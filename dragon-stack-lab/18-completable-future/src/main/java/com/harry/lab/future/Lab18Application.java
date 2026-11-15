package com.harry.lab.future;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 演示：CompletableFuture 的生产真实用法，以及五种组合 API 的对照参考。
 *
 * <p>【先说结论，这一点很重要】在 dragon_harness 的 harness-runner 与 harness_saas
 * 的 src/main 里 grep supplyAsync / thenCombine / thenCompose / allOf / exceptionally /
 * orTimeout，命中数是 0。生产没有用这些组合方法。真实形态是
 * 「手建 CompletableFuture + 虚拟线程完成 + 自建 timer 池」，
 * 本类第一段演示的就是它（SdkApp.java:64-66、CoreTranslation.java:72-82）。
 * 后面五段 API 演示保留，是因为 API 本身值得学，但每一段都标注了
 * 「生产未使用」以及它替代的是生产的哪个手写环节。
 *
 * <p>【第一，为什么手建 future 而不是 supplyAsync】因为生产需要在任务开始前
 * 就拿到 future 交给调用方，同时自己控制「在哪个线程完成」和「取消时中断谁」。
 * supplyAsync 的返回值和执行是同一个动作，做不到「先返回、后决定怎么跑」。
 *
 * <p>【第二，为什么用虚拟线程】一次 run 要等网络、等容器、等 SDK，全是阻塞操作。
 * 虚拟线程在阻塞时自动让出载体线程，所以每任务一个虚拟线程比固定线程池更合适，
 * 也不会出现「池满了新任务排队」的尾延迟（SdkApp 用 Semaphore 显式限流）。
 *
 * <p>【第三，whenCompleteAsync 的意义是换线程】future 在哪个线程完成，
 * 同步回调就在哪个线程跑。生产刻意让 client 侧的 future 在自己的虚拟线程完成
 * （CoreTranslation.java:49-52、75-79），否则回调会跑在 runtime 唯一的
 * stdout reader 上，一阻塞就把整条协议流卡死。
 *
 * <p>【第四，异步 API 的校验失败也走 future】参数非法、已关闭、限额已满这三种
 * 情况，生产返回的是 failedFuture 而不是 throw（JsonLineRpcConnection.java:87、90、92）。
 * 理由很实际：调用方已经习惯了「拿到 future 再处理结果」，如果一半情况 throw、
 * 一半情况 future 失败，每个调用点都要写两套错误处理。
 *
 * <p>【第五，取消必须传播】future.cancel(true) 只标记 future，不会中断执行线程。
 * 生产用 whenComplete 检查 isCancelled 再 interrupt，上游在 InterruptedException
 * 里再 cancel 一次并重新置中断位（CoreTranslation.java:80、85-97；
 * ContainerApp.java:421-426）。
 *
 * <p>【第六，真实需求是背压，不是 allOf】有限的名额和有限的队列，
 * 满了立刻回 CONFLICT，比「全部并发再等 allOf」更贴近生产
 * （JsonLineRpcConnection.java:69-71、91-94、115-117）。
 */
@SpringBootApplication
public class Lab18Application implements CommandLineRunner {

    record RunResult(String sessionId, String output) {}

    static final class CodedException extends RuntimeException {
        private final String code;

        CodedException(String code, String message) {
            super(message);
            this.code = code;
        }

        String code() {
            return code;
        }
    }

    private final Semaphore slots = new Semaphore(2);
    private final ArrayBlockingQueue<String> queue = new ArrayBlockingQueue<>(2);

    @Override
    public void run(String... args) throws Exception {
        productionPattern();
        completionThread();
        earlyFailures();
        cancellation();
        backpressure();
        apiReference();
    }

    private void productionPattern() throws Exception {
        var completedOn = new AtomicReference<String>();
        var future = new CompletableFuture<RunResult>();
        Thread worker = Thread.ofVirtual().name("sdk-run-demo").unstarted(() -> {
            try {
                Thread.sleep(50);
                completedOn.set(Thread.currentThread().getName());
                future.complete(new RunResult("demo", "最终答复"));
            } catch (Throwable failure) {
                future.completeExceptionally(failure);
            }
        });
        future.whenComplete((value, failure) -> {
            if (future.isCancelled()) worker.interrupt();
        });
        worker.start();
        System.out.println("[生产范式] 手建 future = " + future.get().output()
                + "，完成于 " + completedOn.get() + "，等待线程 " + Thread.currentThread().getName());
    }

    private void completionThread() throws Exception {
        var source = new CompletableFuture<String>();
        var mapped = source.whenCompleteAsync((value, failure) ->
                        System.out.println("           回调线程 = " + Thread.currentThread().getName()),
                task -> Thread.ofVirtual().name("harness-client-completion").start(task));
        Thread.ofVirtual().name("dsh-rpc-stdout").start(() -> source.complete("ok"));
        mapped.get();
        System.out.println("[回调线程] future 在 dsh-rpc-stdout 完成，回调被第二个参数换到了另一个线程");
    }

    private void earlyFailures() throws Exception {
        System.out.println("[早失败] 参数非法 = " + codeOf(start(null, false, 0)));
        System.out.println("[早失败] 已关闭   = " + codeOf(start("run", true, 0)));
        System.out.println("[早失败] 限额已满 = " + codeOf(start("run", false, 8)));
    }

    private CompletableFuture<String> start(String input, boolean closed, int inFlight) {
        if (input == null) return CompletableFuture.failedFuture(new CodedException("INVALID_ARGUMENT", "参数非法"));
        if (closed) return CompletableFuture.failedFuture(new CodedException("TRANSPORT_CLOSED", "已关闭"));
        if (inFlight >= 8) return CompletableFuture.failedFuture(new CodedException("CONFLICT", "并发已满"));
        return CompletableFuture.completedFuture("accepted");
    }

    private static String codeOf(CompletableFuture<String> future) {
        try {
            return future.get();
        } catch (ExecutionException failure) {
            return failure.getCause() instanceof CodedException coded ? coded.code() : "未知";
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return "interrupted";
        }
    }

    private void cancellation() throws Exception {
        var stopped = new AtomicBoolean();
        var future = new CompletableFuture<String>();
        Thread worker = Thread.ofVirtual().name("sdk-run-cancel").unstarted(() -> {
            try {
                Thread.sleep(10_000);
                future.complete("done");
            } catch (InterruptedException interrupted) {
                future.cancel(true);
                stopped.set(true);
                Thread.currentThread().interrupt();
            }
        });
        future.whenComplete((value, failure) -> {
            if (future.isCancelled()) worker.interrupt();
        });
        worker.start();
        Thread.sleep(50);
        future.cancel(true);
        worker.join(1_000);
        System.out.println("[取消传播] 已取消 = " + future.isCancelled()
                + "，上游收到中断并重新置位 = " + stopped.get() + "，worker 已结束 = " + !worker.isAlive());
    }

    private void backpressure() {
        int rejected = 0;
        for (int i = 0; i < 5; i++) if (!queue.offer("run-" + i)) rejected++;
        int acquired = 0;
        for (int i = 0; i < 3; i++) if (slots.tryAcquire()) acquired++;
        System.out.println("[背压] 队列容量 2 提交 5 个，拒绝 " + rejected
                + "；并发名额 2 申请 3 个，拿到 " + acquired + " → 拒绝的立刻回 CONFLICT");
    }

    private void apiReference() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var image = CompletableFuture.supplyAsync(() -> slow("镜像元数据", 120), pool);
            var config = CompletableFuture.supplyAsync(() -> slow("任务配置", 80), pool);
            System.out.println("[API 参考] thenCombine = "
                    + image.thenCombine(config, (a, b) -> a + " + " + b).get() + "（生产未使用）");

            var fallback = CompletableFuture.<String>supplyAsync(() -> {
                throw new IllegalStateException("镜像拉取失败");
            }, pool).exceptionally(error -> "降级值（" + error.getCause().getMessage() + "）");
            System.out.println("[API 参考] exceptionally = " + fallback.get() + "（生产未使用）");

            try {
                CompletableFuture.supplyAsync(() -> slow("慢任务", 300), pool)
                        .orTimeout(50, TimeUnit.MILLISECONDS).get();
            } catch (ExecutionException timeout) {
                System.out.println("[API 参考] orTimeout = " + timeout.getCause().getClass().getSimpleName()
                        + "（生产改用自建 timer 池）");
            }

            var parts = List.of(
                    CompletableFuture.supplyAsync(() -> "工作区", pool),
                    CompletableFuture.supplyAsync(() -> "密钥", pool));
            CompletableFuture.allOf(parts.toArray(new CompletableFuture[0])).join();
            System.out.println("[API 参考] allOf = " + parts.stream().map(CompletableFuture::join).toList()
                    + "（生产未使用，create 是同步顺序执行）");
        }
    }

    private String slow(String value, long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        return value;
    }

    public static void main(String[] args) {
        SpringApplication.run(Lab18Application.class, args);
    }
}
