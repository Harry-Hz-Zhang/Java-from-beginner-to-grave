package com.harry.lab.schedule;

import java.util.concurrent.locks.LockSupport;
import java.util.stream.IntStream;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 演示二：一个配置驱动的扫描型定时任务，一个慢定时任务，以及 @Async 的代理陷阱。
 *
 * <p>【这是什么】@Component 把类交给容器，@Scheduled 标在 public 无参方法上，
 * 容器启动后按注解里的时间规则反复调用它。方法体就是「一轮巡检」。
 *
 * <p>【语法怎么写】fixedDelay 填毫秒数，fixedDelayString 填字符串占位符，
 * 运行时从配置里取，取不到就用冒号后面的默认值，写法是 ${键:默认值}。
 * initialDelay 表示启动后先等多久再跑第一次，initialDelayString 同理支持占位符。
 * 一个 @Scheduled 只能选 fixedDelay、fixedRate、cron 三种之一，不能同时写两个。
 *
 * <p>【第一，为什么用占位符而不是直接写 60_000】生产里扫描间隔属于运维参数，
 * 换环境要改的是配置而不是代码，改完重启即可生效，也便于在测试环境把间隔调小。
 * harness_saas 的 RunnerImageApp 第 79 行、infrastructure/registry/DockerRegistryLogin
 * 第 43 行、dragon_harness 的 ContainerMonitor 第 27 行都是这种写法。
 *
 * <p>【第二，为什么几乎不用 cron 和 fixedRate】生产十处定时任务里，七处 fixedDelay、
 * 三处 fixedDelayString，cron 与 fixedRate 都是零处。原因是这些任务都在扫库做补偿，
 * fixedRate 会在上一轮没跑完时叠加触发，同一条数据被处理两次；fixedDelay 是「上一轮
 * 结束之后再等 N 秒」，天然不会重叠。只有必须按整点对齐的场景才值得用 cron。
 *
 * <p>【第三，为什么每轮只取一百条】一次扫全表在数据量大时会把数据库和内存都拖住，
 * 而且单轮耗时不可控。生产用的就是限量批量：TaskApp 第 131 行的 tasks.orphans(100)、
 * TaskRuntimeApp 里的 IDLE_BATCH 常量都是这个模式，剩下的下一轮再扫。
 *
 * <p>【第四，为什么任务体要逐条 try/catch】一轮里有一条数据坏掉，不应该让后面九十九条
 * 都不处理。生产 TaskApp 第 131 到 138 行、TaskRuntimeApp 第 283 到 295 行都是
 * 循环内部捕获 RuntimeException、记一条 warn 后继续下一条。这里为了看清效果，
 * 让第 50 条和第 100 条故意抛异常。
 *
 * <p>【第五，@Async 的代理陷阱】@Async 靠容器的代理对象生效。同一个类里写 this.writeAudit(...)，
 * 调用根本不经过代理，方法就在当前线程上同步执行，而且不报任何错。这和 @Transactional
 * 的自调用失效是同一个机制。triggerWrong 就是错误示范，对照它的线程名就能看出来。
 *
 * <p>【第六，@Async 的硬性要求】方法必须 public，返回值只能是 void、Future 或
 * CompletableFuture，返回别的类型框架无法包装结果。再强调一次：生产代码里没有 @Async。
 */
@Component
public class ScheduledTasks {

    /** 每轮上限。生产里同名常量出现在 TaskApp 与 TaskRuntimeApp。 */
    private static final int LIMIT = 100;

    /** 配置驱动的扫描：间隔默认十分钟，初始延迟默认十五秒。 */
    @Scheduled(fixedDelayString = "${lab.scan-interval:10m}", initialDelayString = "${lab.scan-initial:15s}")
    public void scan() {
        var rows = IntStream.rangeClosed(1, 120).boxed().limit(LIMIT).toList();
        System.out.println("[定时-扫描] 线程 = " + Thread.currentThread().getName()
                + "，本轮取到 " + rows.size() + " 条");
        int failed = 0;
        for (var row : rows) {
            try {
                if (row % 50 == 0) throw new IllegalStateException("模拟单条数据损坏");
            } catch (RuntimeException failure) {
                failed++;
                System.out.println("[定时-扫描] 第 " + row + " 条失败：" + failure.getMessage() + "，本轮继续");
            }
        }
        System.out.println("[定时-扫描] 本轮成功 " + (rows.size() - failed) + " 条，失败 " + failed + " 条");
    }

    /** 慢任务：故意睡一秒半，用来验证调度器是多线程的。 */
    @Scheduled(fixedDelayString = "${lab.scan-interval:10m}", initialDelayString = "${lab.scan-initial:15s}")
    public void slowScan() {
        LockSupport.parkNanos(1_500_000_000L);
        System.out.println("[定时-慢] 睡了一秒半，线程 = " + Thread.currentThread().getName());
    }

    /** 通过代理调用，异步生效，线程名以 audit- 开头。 */
    @Async("auditExecutor")
    public void writeAudit(String message) {
        System.out.println("[异步] " + message + "，线程 = " + Thread.currentThread().getName());
    }

    /** 错误示范：同类内部 this 调用绕过代理，退化成同步执行。 */
    public void triggerWrong(String message) {
        this.writeAudit(message);
    }
}
