package com.harry.lab.ioc;

import jakarta.annotation.*;
import java.time.Clock;
import org.springframework.beans.factory.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * 演示三：Bean 的生命周期回调、带 @Qualifier 的构造器注入、以及两个构造器时的 @Autowired。
 *
 * <p>【生命周期顺序】第一，容器调用构造器；第二，注入构造器参数；第三，调用 @PostConstruct；
 * 第四，Bean 可以被使用；第五，容器关闭时调用 @PreDestroy。这条顺序解释了为什么初始化逻辑
 * 不能写在构造器里：那时其他依赖还没就位。
 *
 * <p>【为什么有两个构造器时 @Autowired 是必须的】第一，Spring 在只有一个构造器时可以自动选中，
 * 不需要注解。第二，一旦出现第二个构造器，容器无法判断该用哪个，必须靠 @Autowired 指定。
 * 生产 application/TaskRuntimeApp.java:49-56 就是这个结构：一个 @Autowired 的 public 构造器
 * 接五个依赖，内部转调一个包私有构造器；包私有的那个多接一个 Clock，专供单元测试传入固定时间。
 * 注意生产并没有把 Clock 注册成 Bean，测试要的固定时间由包私有构造器直接传。
 * 本类的两个构造器照同一思路写，包私有那个不经过容器，测试可以直接 new。
 *
 * <p>【@Qualifier 解决什么问题】容器里有两个 Client 类型的 Bean，名字分别是 transferClient
 * 与 signingClient。只写类型会抛 NoUniqueBeanDefinitionException，写上 @Qualifier 就等于点名。
 * 同一个构造器里两个参数类型相同、只是名字不同，这正是生产
 * infrastructure/storage/AliyunOssStorage.java:37-38 的写法。
 *
 * <p>【@PostConstruct 的真实用途】第一，不是打印日志，而是「启动即校验并占资源」。
 * 第二，生产 config/RunnerOwnership.java:30-45 在 @PostConstruct 里做三件事：
 * 读 owner-lock-enabled 开关、创建数据目录、对目录抢一个文件锁，抢不到就启动失败。
 * 这类动作必须在 Bean 可用之前完成，所以只能放在这个回调里。
 * 第三，本类用它读取 runner.notify-enabled，让开关真正决定是否发通知，
 * 与生产读开关的做法同源。
 *
 * <p>【@PreDestroy 的真实用途】生产 7 处 @PreDestroy 集中在两件事：停掉后台容器、关闭线程池。
 * 例如 ContainerApp#close 停掉所有运行中的 Docker 容器，SdkApp#close 关闭 SDK 会话，
 * ContainerMonitor#close 停掉监控线程。它们的共同点是必须赶在容器销毁前把外部资源收干净。
 */
@Service
public class OrderService {

    private final Notifier notifier; private final AuditNotifier audit;
    private final Client transfer, signing;
    private final Environment environment; private final Clock clock;
    private boolean notifyEnabled = true;

    /**
     * 容器使用的构造器。五个参数里有两个 Client，类型相同，所以两个都要写 @Qualifier。
     * 第一，@Qualifier("consoleNotifier") 选的是 @Component 扫描出来的实现类；
     * 第二，@Qualifier("transferClient") 选的是 LabConfig 里 @Bean(name = ...) 产出的对象。
     * 两种来源在容器里都表现为「有名字的 Bean」，消歧规则完全一致。
     * 时间依赖用 Clock.systemUTC() 在构造器里给出，不需要容器注册 Clock Bean，
     * 这正是生产 application/TaskRuntimeApp.java:52 的做法。
     */
    @Autowired
    public OrderService(@Qualifier("consoleNotifier") Notifier notifier, AuditNotifier audit,
            @Qualifier("transferClient") Client transfer, @Qualifier("signingClient") Client signing,
            Environment environment) {
        this(notifier, audit, transfer, signing, environment, Clock.systemUTC());
    }

    /** 包私有构造器：给单元测试直接 new 用，多一个 Clock 参数，测试可以传 Clock.fixed(...)。 */
    OrderService(Notifier notifier, AuditNotifier audit, Client transfer, Client signing,
            Environment environment, Clock clock) {
        this.notifier = notifier; this.audit = audit;
        this.transfer = transfer; this.signing = signing;
        this.environment = environment; this.clock = clock;
    }

    /** 下单。通知开关关闭时只打印一行，说明开关确实来自 Environment 而不是硬编码。 */
    public void place(String orderId) {
        if (!notifyEnabled) { System.out.println("  [已关闭] 跳过通知 " + orderId); return; }
        notifier.send("下单成功 " + orderId + "，时间 " + clock.instant());
        audit.send(orderId);
    }

    /** 依赖已注满，可以安全地读配置。第二个参数指定类型，第三个参数是没有配置时的默认值。 */
    @PostConstruct
    void onStart() {
        notifyEnabled = environment.getProperty("runner.notify-enabled", Boolean.class, true);
        System.out.println("[生命周期] 依赖已注满，通知开关=" + notifyEnabled);
    }

    /** 容器关闭前释放资源。真实项目里这里要停线程池、关连接，而不是只打印一行。 */
    @PreDestroy
    void onStop() { System.out.println("[生命周期] 容器关闭，释放通知资源"); }
}
