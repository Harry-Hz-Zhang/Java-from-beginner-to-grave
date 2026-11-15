package com.harry.lab.virtual;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 演示：三种创建方式、批量并发密度、ThreadLocal 不继承、pinning 对照实验、
 * 平台线程仍有用武之地的那一处，以及持有句柄后如何收尾。
 *
 * <p>【这是什么】一个 Web 应用，启动时跑几段对照实验，之后常驻，以便用 curl 验证
 * 全局虚拟线程开关是否落地（见 VirtualThreadProbeController）。
 *
 * <p>【第一，虚拟线程解决什么】它不让单个任务变快，它解决的是「阻塞时不要占着操作系统线程」。
 * 平台线程阻塞时载体被占死，只能靠加线程数扛并发，几千个就到顶；虚拟线程阻塞时把载体让出去，
 * 所以能同时挂起十万个任务而只占几个载体线程。
 *
 * <p>【第二，方式一 Thread.ofVirtual()】适合精细控制单个线程：能设名字、设异常处理器，
 * 也能用 unstarted 先拿句柄、稍后再 start。名字要设，默认名是一串编号，日志里认不出来。
 *
 * <p>【第三，方式二 newVirtualThreadPerTaskExecutor()】批量场景最常用，每个任务一个虚拟线程。
 * try-with-resources 退出时会等全部任务结束，这是 Java 21 给 ExecutorService 加的能力。
 * 不要池化虚拟线程：创建成本极低，池化只会引入队列和拒绝策略这些无谓复杂度。
 *
 * <p>【第四，方式三 Thread.startVirtualThread()】一行启动一个，拿不到句柄做后续控制。
 * 三种方式底层是同一套 API，区别只在控制粒度。
 *
 * <p>【第五，生产怎么起线程】两个仓的主代码里没有一处 newVirtualThreadPerTaskExecutor，
 * 那是测试里才用的写法。生产用 Thread.ofVirtual().name(...).unstarted(...) 先拿句柄存进字段，
 * 再 start，并在 @PreDestroy 里 interrupt，这样容器关闭时能主动收尾。见 RunnerImagePullApp
 * 第 87 行与第 92 到 95 行、SessionLogApp 第 118 行（它还挂了 uncaughtExceptionHandler）。
 *
 * <p>【第六，pinning 是什么】JDK 21 里虚拟线程在 synchronized 块内阻塞时，会把载体线程
 * 一起钉住，此时它退化成平台线程，并发度掉到载体线程数。下面的 blocking 方法只改一行：
 * 用 synchronized 时六十四个人各睡两百毫秒要一秒六，用 ReentrantLock 只要两百毫秒。
 * 生产规避这个问题的办法就是 ReentrantLock，见 RunnerImageApp 第 36 行、
 * dragon_harness 的 ContainerApp 第 61 行。JDK 24 起这个限制已经解除，但生产跑的是 21。
 *
 * <p>【第七，有些线程不该虚拟化】定时器线程是典型：它大部分时间在排队等触发，阻塞极短，
 * 用虚拟线程没有收益，而 ScheduledThreadPoolExecutor 需要一个稳定的平台线程来守时。
 * 生产 JsonLineRpcConnection 第 73 行用 Thread.ofPlatform().daemon().name("dsh-rpc-timeout-", 0)
 * 造定时器工厂，守护属性保证它不阻止 JVM 退出。这也是本模块唯一一处「平台线程更好」。
 *
 * <p>【第八，ThreadLocal 的风险】每个虚拟线程一份副本，一万个虚拟线程就是一万份数据，
 * 存大对象会迅速吃光内存。它也不跨线程传递，所以异步链路里传上下文要靠显式传参。
 */
@SpringBootApplication
public class Lab12Application implements CommandLineRunner {

    private static final ThreadLocal<String> TRACE_ID = new ThreadLocal<>();
    private static final int TASKS = 64;

    /** 长任务的工作线程句柄，关停时要靠它中断。 */
    private volatile Thread worker;

    public static void main(String[] args) {
        SpringApplication.run(Lab12Application.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        Thread.ofVirtual().name("vt-single").start(
                () -> System.out.println("方式一，线程 = " + Thread.currentThread())).join();
        System.out.println("方式二，五千个阻塞五十毫秒的任务耗时 " + bulk() + " 毫秒");
        Thread.startVirtualThread(() -> System.out.println("方式三，线程 = " + Thread.currentThread())).join();
        TRACE_ID.set("main 的追踪号");
        Thread.ofVirtual().start(() -> System.out.println("子虚拟线程读到的追踪号 = " + TRACE_ID.get())).join();
        System.out.println("main 自己的追踪号 = " + TRACE_ID.get());
        worker = Thread.ofVirtual().name("vt-worker").unstarted(this::waitForever);
        worker.start();
        System.out.println("六十四任务：synchronized 内阻塞 " + blocking(false) + " 毫秒，ReentrantLock 内阻塞 " + blocking(true) + " 毫秒");
        timerDemo();
    }

    /** 一直睡着，直到容器关闭时被 interrupt，这是生产收尾长任务的固定写法。 */
    private void waitForever() {
        try {
            Thread.sleep(60_000);
        } catch (InterruptedException interrupted) {
            System.out.println("worker 收到 interrupt 后退出，线程 = " + Thread.currentThread().getName());
        }
    }

    /** 同一件事的两种锁。每个任务用自己的监视器或锁，彼此没有竞争，只观察阻塞时是否让出载体。 */
    private long blocking(boolean useLock) throws InterruptedException {
        var start = System.currentTimeMillis();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < TASKS; i++) {
                executor.submit(() -> {
                    var lock = new ReentrantLock();
                    if (useLock) {
                        lock.lock();
                        try { Thread.sleep(200); } finally { lock.unlock(); }
                    } else {
                        synchronized (new Object()) { Thread.sleep(200); }
                    }
                    return null;
                });
            }
        }
        return System.currentTimeMillis() - start;
    }

    /** 五千个挂起五十毫秒的任务，虚拟线程几乎同时挂起，总耗时接近一次阻塞。 */
    private long bulk() throws InterruptedException {
        var start = System.currentTimeMillis();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 5000; i++) {
                executor.submit(() -> { LockSupport.parkNanos(50_000_000L); return null; });
            }
        }
        return System.currentTimeMillis() - start;
    }

    /** 定时器用平台线程工厂，守护属性，任务结束不影响进程退出。 */
    private void timerDemo() {
        var timers = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("lab-timer-", 0).factory());
        timers.scheduleAtFixedRate(() -> System.out.println("定时线程 = " + Thread.currentThread().getName()
                + "，是否虚拟 = " + Thread.currentThread().isVirtual()), 0, 200, TimeUnit.MILLISECONDS);
        timers.schedule(timers::shutdown, 500, TimeUnit.MILLISECONDS);
    }

    /** 容器关闭时中断仍在运行的 worker。 */
    @PreDestroy
    void stop() throws InterruptedException {
        if (worker == null) return;
        worker.interrupt();
        worker.join(1000);
        System.out.println("@PreDestroy 已中断 worker，线程 = " + Thread.currentThread().getName());
    }
}
