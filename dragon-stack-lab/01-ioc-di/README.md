# 01 控制反转与依赖注入

## 演示什么

这个模块用四个类型演示 Spring 最底层的三件事：对象由谁创建、依赖由谁传递、对象什么时候被销毁。它不引入 Web，所以你能在一个纯控制台程序里看清容器的行为：启动钩子执行一次下单，容器关闭时回收资源。写法对齐生产项目的 `harness_saas` 与 `harness-runner-core`。

## 怎么运行

```bash
mvn -pl 01-ioc-di spring-boot:run
```

预期输出依次是：`@PostConstruct` 里读到的通知开关、控制台通知、审计留档，以及容器关闭前的 `@PreDestroy` 打印。想验证开关来自配置而不是硬编码，可以覆盖环境变量：

```bash
RUNNER_NOTIFY_ENABLED=false mvn -pl 01-ioc-di spring-boot:run
```

此时通知会被跳过，只打印一行「已关闭」。

## 关键语法点

第一，`@SpringBootApplication` 是 `@SpringBootConfiguration`、`@EnableAutoConfiguration`、`@ComponentScan` 三个注解的组合，启动类必须放在根包，否则扫描范围不对。

第二，构造器注入优于字段注入。字段声明为 `final`，依赖缺失时启动即报错，而不是运行时空指针。

第三，单构造器可以不写 `@Autowired`。这是 Spring 4.3 之后的行为，生产项目里因此几乎看不到这个注解。一旦类里有第二个构造器，`@Autowired` 就从可选变成必须。

第四，`@Configuration(proxyBeanMethods = false)` 表示不需要 CGLIB 代理配置类。生产三个配置类全部这么写，因为它们的 `@Bean` 方法之间不互相调用，代理没有作用。

第五，同一类型有多个 Bean 时，生产主代码的做法是 `@Bean(name = "...")` 命名加构造器 `@Qualifier` 点名，不是 `@Primary`。`@Primary` 在生产主代码里零处，只在测试里用来替换真实 Bean。

第六，`@Bean` 用于注册改不了源码的类。方法名即 Bean 名，`name` 属性可以再显式指定一次；`destroyMethod` 可以让容器在关闭时调用第三方对象的清理方法。

第七，生命周期回调顺序是构造器、依赖注入、`@PostConstruct`、使用、`@PreDestroy`。`@PostConstruct` 的真实用途是启动即校验并占资源，不是打印日志。

## 完整用法清单

| 用法 | 生产项目位置 |
| --- | --- |
| `@Bean` 注册第三方类 | `harness_saas/config/PersistenceConfiguration.java` 注册 `MybatisPlusInterceptor` 与 `MetaObjectHandler`，两个类都改不了源码 |
| `@Bean(name = "...")` 两个同类型 Bean | `harness_saas/config/OssConfiguration.java:23-31`，`ossTransferClient` 与 `ossSigningClient` |
| `@Bean(destroyMethod = "shutdown")` | 同上，容器关闭时自动调用 OSS 客户端的 `shutdown` |
| `@Qualifier` 构造器消歧 | `infrastructure/storage/AliyunOssStorage.java:37-38`，两个 `OSS` 参数靠名字区分 |
| 多构造器 `@Autowired` | `application/TaskRuntimeApp.java:49-56`，public 构造器接五个依赖，包私有构造器多接一个 `Clock` |
| `@PostConstruct` | `harness-runner-core/.../config/RunnerOwnership.java:30-45`，读开关、建目录、抢文件锁 |
| `@PreDestroy` | `ContainerApp#close`、`SdkApp#close`、`ContainerMonitor#close`，全仓七处都在停容器或关线程池 |
| `@ConditionalOnMissingBean` | `HarnessRunnerCoreAutoConfiguration.java:53,59`，让宿主替换容器网关与 RPC 工厂 |
| `@ConditionalOnProperty` | 同文件 `:83`，`runner.monitor-enabled` 控制监控 Bean 是否注册 |
| `@Bean ApplicationRunner` | `config/SecurityConfiguration.java:101-106`，启动时做完整性校验；全仓零 `CommandLineRunner` |
| `ThreadLocal` | 不是 Spring 特性，容器不参与它的生命周期。生产主代码零处，不要把它当成作用域 Bean 的替代 |

## 自测三问

第一，把 `OrderService` 构造器上的两个 `@Qualifier` 删掉，启动会怎样？容器发现两个 `Client` 候选，抛 `NoUniqueBeanDefinitionException`。这说明按类型注入在同类型多 Bean 时必须消歧。

第二，把 `proxyBeanMethods` 改回 `true` 会怎样？功能不变，但配置类被 CGLIB 代理，调用多一层间接。本模块 `@Bean` 方法互不调用，所以关掉更直接。

第三，让启动类去实现 `CommandLineRunner` 与写 `@Bean ApplicationRunner` 有什么差别？前者会在容器里多一个启动类自身类型的 Bean，职责也混在一起；后者只登记一个 `ApplicationRunner` Bean，生产项目采用的正是后一种。

## 行数说明

四个文件合计六十八行业务代码（注释不计），在七十行纪律之内。
