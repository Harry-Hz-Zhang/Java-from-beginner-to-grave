package com.harry.lab.autoconfig;

import com.harry.lab.library.GreetingService;
import com.harry.lab.library.MonitorTask;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 演示四：把库当宿主跑起来，验证自动配置的五个结论。
 *
 * <p>【这跑出来看什么】第一行证明清单文件生效；[lock] 早于 [container] 证明 @DependsOn
 * 真的锁住了顺序；[monitor] 两行证明 @EnableScheduling 加 before 的组合建出了调度器，
 * 并打印实际的 TaskScheduler 类型；监控 Bean 是否存在，取决于 matchIfMissing 的取值。
 *
 * <p>【怎么跑两次】不配 monitor-enabled 时监控默认开；加 --runner.monitor-enabled=false
 * 才关。对比两次数出来的行数即可，不需要改代码。
 *
 * <p>【为什么最后 close】@EnableScheduling 会留下一个非守护线程池，
 * 不关上下文 JVM 不会退出。等两拍就关，是为了让 demo 自己结束。
 */
@SpringBootApplication
public class Lab15Application implements CommandLineRunner {

    private final ConfigurableApplicationContext context;

    public Lab15Application(ConfigurableApplicationContext context) {
        this.context = context;
    }

    @Override
    public void run(String... args) throws Exception {
        System.out.println(context.getBean(GreetingService.class).greet());
        System.out.println("DebugReporter 是否注册 = " + context.containsBean("debugReporter"));
        System.out.println("MonitorTask 是否注册 = " + context.containsBean("harnessRunnerMonitorTask"));

        var monitor = context.getBeanProvider(MonitorTask.class).getIfAvailable();
        if (monitor == null) {
            System.out.println("[monitor] 已被显式关闭，没有 reconcile 节拍");
        } else {
            System.out.println("[monitor] 等到两拍 = " + monitor.awaitTicks(5));
        }
        context.close();
    }

    public static void main(String[] args) {
        SpringApplication.run(Lab15Application.class, args);
    }
}
