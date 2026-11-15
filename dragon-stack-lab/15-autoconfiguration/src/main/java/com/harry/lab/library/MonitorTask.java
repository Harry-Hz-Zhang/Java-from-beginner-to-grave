package com.harry.lab.library;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 演示四：一个带 @Scheduled 的可选组件，用来证明 @EnableScheduling 与
 * @AutoConfiguration(before = TaskSchedulingAutoConfiguration.class) 是配套使用的。
 *
 * <p>【这是什么】生产里对应的类是 ContainerMonitor：它每 runner.monitor-interval
 * 触发一次 reconcile，把 Docker 事件漏掉的容器状态补回来。这里只保留节拍，
 * 打印当前线程和实际生效的 TaskScheduler 类型，让你看见调度器是谁建的。
 *
 * <p>【语法怎么写】@Scheduled(fixedDelayString = "${runner.monitor-interval:10s}")：
 * fixedDelay 表示「上一次执行结束后再等这么久」，字符串占位符支持 Duration 写法（10s）。
 * 冒号后面是「属性缺失时的默认值」，和 matchIfMissing 是两回事，后者决定 Bean 装不装。
 *
 * <p>【为什么这里需要 before】@Scheduled 本身只登记方法，真正执行要靠容器里的
 * TaskScheduler。Boot 的 TaskSchedulingAutoConfiguration 只在「已经有人开了
 * @EnableScheduling」时才建这个调度器，所以本类所在的自动配置必须排在它前面，
 * 先把 @EnableScheduling 打开，否则绑定阶段看不到调度器。
 *
 * <p>【生产在哪用】HarnessRunnerCoreAutoConfiguration:33-34 同时写着
 * @AutoConfiguration(before = TaskSchedulingAutoConfiguration.class) 和 @EnableScheduling，
 * ContainerMonitor.java:27 就是这个 @Scheduled。
 */
public class MonitorTask {

    private final RunnerProperties properties;
    private final ObjectProvider<TaskScheduler> scheduler;
    private final AtomicInteger ticks = new AtomicInteger();
    private final CountDownLatch firstTicks = new CountDownLatch(2);

    public MonitorTask(RunnerProperties properties, ObjectProvider<TaskScheduler> scheduler) {
        this.properties = properties;
        this.scheduler = scheduler;
    }

    @Scheduled(fixedDelayString = "${runner.monitor-interval:10s}")
    public void inspect() {
        var current = scheduler.getIfAvailable();
        System.out.println("[monitor] 第 " + ticks.incrementAndGet() + " 次 reconcile，间隔 "
                + properties.monitorInterval().toSeconds() + " 秒，执行线程 "
                + Thread.currentThread().getName() + "，TaskScheduler "
                + (current == null ? "未提供" : current.getClass().getName()));
        firstTicks.countDown();
    }

    public boolean awaitTicks(long seconds) throws InterruptedException {
        return firstTicks.await(seconds, TimeUnit.SECONDS);
    }
}
