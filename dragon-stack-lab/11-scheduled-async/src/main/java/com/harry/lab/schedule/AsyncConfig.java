package com.harry.lab.schedule;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 演示一：给定时任务一个多线程调度器，给 @Async 一个独立线程池。
 *
 * <p>【这是什么】这是一个配置类，负责往容器里放两个线程池：一个给 @Scheduled 用，
 * 一个给 @Async 用。线程池本身不是业务代码，但它决定了业务代码在哪个线程上跑，
 * 出问题时线程名就是第一手线索。
 *
 * <p>【语法怎么写】在类上写 @Configuration 表示这是配置类；方法上写 @Bean 表示
 * 返回值交给容器管理，方法名默认就是 Bean 名。@EnableScheduling 是 @Scheduled 的开关，
 * @EnableAsync 是 @Async 的开关，两者互相独立。
 *
 * <p>【第一，为什么定时任务要自己配调度器】Boot 默认的 TaskScheduler 只有一个线程。
 * 本模块有两个定时任务，在单线程下会串行：慢任务睡一秒半，另一个任务就跟着晚一秒半。
 * 生产项目 harness_saas 的 config/TaskConfiguration.java 第 18 到 24 行做的正是这件事，
 * poolSize 设为 8，前缀设为 task-scheduler-。
 *
 * <p>【第二，生产配它的真实动机】harness_saas 打开了 spring.threads.virtual.enabled，
 * 开启虚拟线程后 Boot 会把默认调度器换成虚拟线程调度器，多个 fixed-delay 任务会被挤到
 * 同一个载体线程上。一次慢的 Docker 调用或 OSS 调用就会把其余定时任务一起拖住，
 * 所以生产显式给调度器一个 8 线程的池，任务之间互不牵连。
 *
 * <p>【第三，@EnableScheduling 该写在哪】本模块写在配置类上，是宿主自己开的开关。
 * 生产里还有另一种写法：dragon_harness 的 HarnessRunnerCoreAutoConfiguration 第 34 行
 * 把 @EnableScheduling 写在库的自动配置类上，宿主应用什么都不用加，只要引入这个库
 * 就自动获得调度能力。这种「被动获得」的开关很容易被忽略，排查定时任务不生效时要先看它。
 *
 * <p>【第四，@Async 为什么还留着】生产两个仓里 @Async 是零处，@EnableAsync 也是零处，
 * 异步是用 Thread.ofVirtual() 直接起虚拟线程（见 TaskRunExecutor 第 94 行、
 * RunnerImagePullApp 第 87 行）。这里保留 @Async 只是因为它「同类调用绕过代理」的陷阱
 * 在 @Transactional 上同样成立，属于值得知道的通用知识；不要以为生产在用它。
 *
 * <p>【第五，线程池四个参数怎么定】corePoolSize 是常驻线程数，maxPoolSize 是队列满后的上限，
 * queueCapacity 是排队长度，超过就触发拒绝策略，threadNamePrefix 是日志里的线程名前缀。
 * 这四个值要按任务性质定：IO 型任务可以多给线程，CPU 型任务给了也没用。
 *
 * <p>【第六，需要手动初始化吗】不需要。ThreadPoolTaskExecutor 与 ThreadPoolTaskScheduler
 * 都实现了 InitializingBean，容器创建 Bean 时会自动调用 afterPropertiesSet，
 * 直接 new 出来的 java.util.concurrent 原生线程池才需要自己管生命周期。
 */
@Configuration
@EnableScheduling
@EnableAsync
public class AsyncConfig {

    /** 定时任务专用调度器。Bean 名必须是 taskScheduler，Boot 的默认调度器才会让位。 */
    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(8);
        scheduler.setThreadNamePrefix("task-scheduler-");
        return scheduler;
    }

    /** @Async 专用线程池。名字写进 @Async("auditExecutor") 才能选中它。 */
    @Bean(name = "auditExecutor")
    public Executor auditExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("audit-");
        return executor;
    }
}
