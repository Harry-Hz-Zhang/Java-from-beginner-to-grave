# 15 自建 Starter 与自动配置

## 演示什么

一个库怎么在**不加任何 `@Component`、不被宿主扫描到**的情况下，把自己的 Bean 装进宿主的容器。整个机制只有两样东西：一个带 `@AutoConfiguration` 的类，和一个固定路径的清单文件。这是 `harness-runner-core` 这个模块存在的全部理由——它是个库，不是应用。

本模块额外演示四条容易「写了但没生效」的细节：`matchIfMissing` 的默认值语义、`static` 配置转换器、`@DependsOn` 的初始化顺序、`@EnableScheduling` 与 `before` 的配套关系，以及一个 starter 登记多组配置前缀。

## 怎么运行

跑三次，对比三组输出。

```bash
# 第一次：只用默认值，一个开关都不配
mvn -pl 15-autoconfiguration spring-boot:run -Dspring-boot.run.arguments=--runner.monitor-interval=1s

# 第二次：显式关掉监控
mvn -pl 15-autoconfiguration spring-boot:run -Dspring-boot.run.arguments="--runner.monitor-interval=1s --runner.monitor-enabled=false"

# 第三次：打开调试开关
mvn -pl 15-autoconfiguration spring-boot:run -Dspring-boot.run.arguments="--runner.monitor-interval=1s --runner.debug=true"
```

第一次的真实输出：

```
[lock] 已取得数据根锁 /.../15-autoconfiguration/./target/data
[container] 第一次容器操作，此时数据根锁已就绪
[monitor] 第 1 次 reconcile，间隔 1 秒，执行线程 scheduling-1，TaskScheduler org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
Runner 已就绪，镜像=dragon-harness:local，监控间隔=1 秒，数据根=./target/data，SDK 并发上限=128
DebugReporter 是否注册 = false
MonitorTask 是否注册 = true
[monitor] 第 2 次 reconcile，间隔 1 秒，执行线程 scheduling-1，TaskScheduler org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
[monitor] 等到两拍 = true
```

第二次只差三行：

```
DebugReporter 是否注册 = false
MonitorTask 是否注册 = false
[monitor] 已被显式关闭，没有 reconcile 节拍
```

`[lock]` 一定在 `[container]` 前面，这是 `@DependsOn` 的效果。`[monitor]` 里的 `ThreadPoolTaskScheduler` 是 Boot 的 `TaskSchedulingAutoConfiguration` 建的，能拿到它说明 `before` 与 `@EnableScheduling` 的配合生效了。

## 这个模块的包结构是故意这样分的

```
com.harry.lab.library     ← 库。GreetingService 上一个 Spring 注解都没有
com.harry.lab.autoconfig  ← 宿主。@SpringBootApplication 在这里
```

两个包**故意不互为子包**。如果库的包在 `com.harry.lab.autoconfig` 底下，宿主的组件扫描会顺带把它扫到，`@AutoConfiguration` 到底有没有通过清单文件生效就验证不出来了——扫描和 imports 都能把 Bean 装上，你分不清是哪条路。

## 关键语法点

**第一，`@AutoConfiguration` 和 `@Configuration` 的区别不在能不能声明 Bean，而在身份和顺序。** 功能上两者都能写 `@Bean`。`@AutoConfiguration` 额外标明「这个类来自库」，并支持 `before` / `after` 排序。生产 `HarnessRunnerCoreAutoConfiguration` 写着 `before = TaskSchedulingAutoConfiguration.class`，因为它自己开 `@EnableScheduling`，必须赶在 Boot 的任务调度自动配置之前完成，否则容器里没有调度器。

**第二，光有注解一定不生效，必须有清单文件。** Boot 不会扫描库的包，它只读一个固定路径：

```
META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

文件内容一行一个全类名。**文件名改一个字就完全不生效，而且不报任何错**——这是新手最常卡住的地方。Boot 2.7 之前用的是 `META-INF/spring.factories`，现在已废弃，老教程里的写法在新版本上会静默失效。

**第三，`@ConditionalOnMissingBean` 是「可替换」的实现方式。** 语义是「容器里没有这个类型的 Bean 时才注册我这个」。宿主想用自己的实现，只要声明一个同类型 Bean，库的自动让位。生产用它让容器网关 `ContainerGateway` 和 RPC 连接工厂 `RpcConnectionFactory` 都能被替换。

**第四，`@ConditionalOnProperty` 的 `matchIfMissing` 决定「不配时算开还是算关」。** 不写这个属性时默认是 `false`，也就是「不配 = 关」；写成 `true` 就是「不配 = 开」，只有显式写 `false` 才关。生产 `:82` 是 `@ConditionalOnProperty(name = "runner.monitor-enabled", havingValue = "true", matchIfMissing = true)`，所以监控默认开启。要注意它控制的对象：被控制的是 `ContainerMonitor` 这个**定时 reconcile 任务**（每 `runner.monitor-interval` 检查一次容器状态），不是「监控线程」——线程是 `TaskScheduler` 的，不会因为这个属性少一个。

**第五，`static @Bean @ConfigurationPropertiesBinding` 里的 `static` 不能省。** 配置绑定发生在 Bean 实例化之前。实例方法形式的 `@Bean` 要求容器先把自动配置类本身创建出来，等于在绑定阶段提前实例化了自动配置对象——它此时拿不到任何配置，初始化逻辑还可能干别的事。`static` 让容器直接调用类方法，跳过这一步。生产在 `:40-44` 用的就是这个写法。

**第六，`@DependsOn` 保证初始化顺序，但不注入引用。** 它只保证被依赖的 Bean 先完成初始化。本模块里 `ContainerRunner` 的构造器是空的——如果不写 `@DependsOn`，两个 Bean 的初始化先后没有任何保证，「第一次容器操作」可能跑在「拿到数据根锁」之前。生产 `:70` 用 `@DependsOn("harnessRunnerOwnership")` 锁住这个顺序，因为容器操作必须在数据根租约之后。

**第七，`@EnableScheduling` 与 `@AutoConfiguration(before = TaskSchedulingAutoConfiguration.class)` 必须成对出现。** `@Scheduled` 只登记方法，真正执行要靠容器里的 `TaskScheduler`；而 Boot 的 `TaskSchedulingAutoConfiguration` 只在「已经有人开了调度」时才建这个调度器。所以本类既写 `before` 又写 `@EnableScheduling`，`MonitorTask` 的 `@Scheduled(fixedDelayString = "${runner.monitor-interval:10s}")` 才能跑起来。只写注释不写注解，等于讲了没演示。

**第八，`@EnableConfigurationProperties` 可以一次登记多组配置。** 数组里放几个类就注册几个配置对象。一个 starter 装多组前缀是常态：生产登记了 `{RunnerProperties, SdkProperties, SubscriptionProperties}` 三组，本模块登记了 `runner` 和 `runner.sdk` 两组。

**第九，库模块里零 `@Component`。** 一个 `@Component` 在库里等于「宿主必须扫到我的包」，而宿主的包名你控制不了。所以 `GreetingService`、`MonitorTask` 都是纯 POJO，由自动配置类用 `@Bean` 方法创建。

## 完整用法清单

| 写法 | 作用 | 生产项目里的位置 |
| --- | --- | --- |
| `@AutoConfiguration` | 声明这是库的自动配置类 | `HarnessRunnerCoreAutoConfiguration` |
| `@AutoConfiguration(before = X.class)` | 抢在别的自动配置之前处理 | 同上，`before = TaskSchedulingAutoConfiguration.class` |
| `@EnableScheduling` + 上一条 | 让 `@Scheduled` 真正跑起来 | `:33-34`，配合 `ContainerMonitor.java:27` |
| `@Scheduled(fixedDelayString = "${...:10s}")` | 固定延迟的周期任务，间隔可配 | `ContainerMonitor.inspect()` |
| `META-INF/spring/...AutoConfiguration.imports` | 让 Boot 找到这个类，**必须** | 同模块 `resources/META-INF/spring/` 下 |
| `@Bean` 方法 | 库里创建 Bean 的唯一合法方式 | `harnessRunnerContainerGateway`、`harnessRunnerRpcConnectionFactory` 等 |
| `@ConditionalOnMissingBean` | 允许宿主覆盖 | 容器网关 `ContainerGateway`、RPC 工厂 `RpcConnectionFactory` |
| `@ConditionalOnProperty(..., matchIfMissing = true)` | 默认开启、显式 `false` 才关 | `runner.monitor-enabled` 控制 `ContainerMonitor` 定时任务 |
| `@ConditionalOnProperty(..., havingValue = "true")` | 默认关闭、显式 `true` 才开 | 本模块的 `runner.debug` |
| `@DependsOn("其它Bean名")` | 只保证初始化顺序，不注入 | `:70`，容器操作晚于数据根租约 |
| `@EnableConfigurationProperties({A, B, C})` | 一次登记多组配置对象 | `RunnerProperties`、`SdkProperties`、`SubscriptionProperties` |
| `static @Bean @ConfigurationPropertiesBinding` | 注册类型转换器，且不提前实例化自动配置类 | `harnessRunnerPathConverter`（`:40-44`） |
| 库内零 `@Component` | 不依赖宿主的扫描路径 | `harness-runner-core` 整个模块 |

## 自测三问

**问一**：把 `imports` 文件里的类名删掉再运行，会看到什么？

答：`NoSuchBeanDefinitionException`，因为 `GreetingService` 从来没被注册过。代码一个字没改，只是清单没了。这就是「注解写了不等于生效」的最直观证明。

**问二**：`@ConditionalOnProperty` 不写 `matchIfMissing` 和写 `true`，行为差在哪？

答：不写等于 `false`，属性缺失时条件不满足，Bean 不注册；写了 `true`，属性缺失时条件满足，Bean 注册，只有显式写 `havingValue` 之外的值（这里是 `false`）才关。生产选后者，因为「监控默认开、出事才有数据」比「忘了配就没有监控」安全。

**问三**：`static @Bean` 里的 `static` 去掉会怎样？

答：编译能过，启动可能看不出问题，但配置绑定阶段容器必须先把自动配置类实例化才能调用这个实例方法，等于提前创建了自动配置对象。一旦自动配置类的构造器或字段初始化依赖某个还没绑定的配置，就会拿到空值或抛异常，而且报错位置离真正原因很远。所以生产把它写成 `static`。

## 对应生产项目的位置

| 这里的概念 | 生产项目的用法 |
| --- | --- |
| 自动配置类 | `harness-runner-core/.../autoconfigure/HarnessRunnerCoreAutoConfiguration.java` |
| imports 清单 | `harness-runner-core/src/main/resources/META-INF/spring/...imports` |
| 定时 reconcile | `.../application/ContainerMonitor.java` |
| `@ConditionalOnMissingBean` | 容器网关 `ContainerGateway`、RPC 连接工厂 `RpcConnectionFactory` 都允许替换 |
| `@ConditionalOnProperty` | `runner.monitor-enabled`，`matchIfMissing = true` |
| `@DependsOn` | `:70` 的 `harnessRunnerOwnership` |
| 配置对象 | `RunnerProperties`、`SdkProperties`、`SubscriptionProperties` |

## 行数说明

十个类合计约一百八十行代码（不含注释）。其中 `HarnessRunnerAutoConfiguration` 一个文件放了三个内部类（转换器、数据根锁、容器操作），因为它们各自只有四到八行，单独拆文件会让读者在五个文件之间跳。
