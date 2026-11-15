# 11 定时任务与异步执行

## 演示什么

`@Scheduled` 怎么按固定延迟反复执行、多个任务怎么落在不同调度线程上，以及 `@Async` 那个和
`@Transactional` 同源的代理陷阱：同类内部调用会绕过代理，注解静默失效。

先说清定位，免得把 demo 当成本项目的实际写法。生产两个仓里 `@Scheduled` 共十处（`harness_saas` 九处、
`dragon_harness` 一处），其中七处 `fixedDelay`、三处 `fixedDelayString`，`cron` 与 `fixedRate` 都是零处；
`@EnableScheduling` 两处；`ThreadPoolTaskScheduler` 一个。而 `@Async` 零处、`@EnableAsync` 零处、
`ThreadPoolTaskExecutor` 零个——生产是靠 `Thread.ofVirtual().name(...).start(...)` 直接起虚拟线程
（`application/TaskRunExecutor.java:94`、`application/RunnerImagePullApp.java:87`）。
这个模块演示 `@Async`，是因为它的代理陷阱在 `@Transactional` 上同样成立，值得知道；
但不要以为生产在用它。

## 怎么运行

```bash
mvn -pl 11-scheduled-async spring-boot:run
```

`application.yaml` 里把扫描间隔调成了 2 秒、初始延迟 500 毫秒，生产默认分别是 10m 与 15s。
真实输出。

```
[异步] 走 this 调用，应当在 main 线程上执行，线程 = main
[异步] 走代理调用，应当在 audit- 线程上执行，线程 = audit-1
[定时-扫描] 线程 = task-scheduler-2，本轮取到 100 条
[定时-扫描] 第 50 条失败：模拟单条数据损坏，本轮继续
[定时-扫描] 第 100 条失败：模拟单条数据损坏，本轮继续
[定时-扫描] 本轮成功 98 条，失败 2 条
[定时-慢] 睡了一秒半，线程 = task-scheduler-1
[定时-扫描] 线程 = task-scheduler-2，本轮取到 100 条
...
[定时-慢] 睡了一秒半，线程 = task-scheduler-3
主线程结束，容器关闭时会把线程池里剩余任务跑完
```

前两行调用的是同一个方法，唯一差别是一个走 `this`、一个走容器注入的代理，线程名从 `main` 变成
`audit-1`；两行的先后顺序每次可能不同，因为异步那一行本来就不保证在 `this` 调用之前打印。
扫描任务与慢任务落在 `task-scheduler-2`、`task-scheduler-1` 两个不同线程上，
说明调度器确实有八个线程，慢任务没有挡住扫描。

## 关键语法点

第一，`@EnableScheduling` 管 `@Scheduled`，`@EnableAsync` 管 `@Async`，两个开关互相独立，漏开一个不报错，只是注解不生效。

第二，`fixedDelay` 是「上次执行完再等 N」，`fixedRate` 是「每 N 触发一次不管上次是否跑完」，`cron` 按日历时间。生产十处定时任务七处 `fixedDelay`、三处 `fixedDelayString`，`cron` 与 `fixedRate` 都不用，因为扫描类任务用 `fixedRate` 会重叠执行，同一条数据被处理两次。

第三，`fixedDelayString` 写成 `${lab.scan-interval:10m}`，冒号后面是缺省值，格式是 Spring 的 `Duration`，`10m` 与 `600000` 等价。好处是改间隔不用改代码。

第四，`initialDelay` 几乎总要写，否则应用一启动就执行一次，此时依赖的服务可能还没就绪。

第五，默认调度器只有一个线程，五个定时任务会串行，一个卡住其余全部延迟。本模块显式给了一个 `poolSize` 为 8 的 `ThreadPoolTaskScheduler`，生产 `config/TaskConfiguration.java:18-24` 同理。生产的真实动机还多一层：开了 `spring.threads.virtual.enabled` 之后，Boot 的默认调度器会把多个 fixed-delay 任务挤到同一个载体线程上，一次慢 Docker 或 OSS 调用就能拖住全部定时任务。

第六，任务体要逐条 try/catch。一轮里一条数据坏掉，不应该让后面九十九条都不处理；生产 `application/TaskApp.java:131-138`、`application/TaskRuntimeApp.java:283-295` 都是循环内捕获 `RuntimeException`、记一条 warn 后继续。

第七，每轮只取一批。生产 `tasks.orphans(100)` 与 `IDLE_BATCH` 都是限量批量，剩下的下一轮再扫，避免一次扫全表把数据库和内存拖住。

补一句纠偏：`TaskRunExecutor` 那两个六十秒一轮的任务不是「扫超时任务与超长运行任务」，而是 `stopOverdue`（已运行时间超过 `harness.task.max-run-duration` 的运行就地取消并把状态写成失败）与 `reconcile`（本进程里没有对应执行记录、又迟迟没有终态的孤儿 run，直接收尾成失败）。前者看的是运行的开始时间，后者看的是「孤儿」这个条件，不是同一件事。

第八，`@Async` 必须经由代理调用，同类 `this` 调用退化成同步执行；方法必须 `public`，返回值只能是 `void`、`Future` 或 `CompletableFuture`。

第九，非 Web 应用里调度线程会阻止 JVM 退出，演示完要主动 `SpringApplication.exit`。

## 完整用法清单

| 写法 | 说明 | 生产位置 | 生产是否使用 |
| --- | --- | --- | --- |
| `fixedDelay` | 上次结束后再等 N | `TaskRunExecutor.java:139,153`、`RuntimeRecordApp.java:120` 等 | 使用，7 处 |
| `fixedDelayString` | 间隔从配置读 | `RunnerImageApp.java:79`、`DockerRegistryLogin.java:43`、`ContainerMonitor.java:27` | 使用，3 处 |
| `fixedRate` | 每 N 触发，不看上次 | 无 | 未使用 |
| `cron` | 日历表达式 | 无 | 未使用 |
| `initialDelay` | 启动后延迟首轮 | `TaskRunExecutor.java:153`、`TaskApp.java:129` | 使用 |
| `initialDelayString` | 延迟也从配置读 | `ContainerMonitor.java:27` | 使用 |
| `zone` | 时区，只对 `cron` 有意义 | 无 | 未使用 |
| `ThreadPoolTaskScheduler` | 多线程调度器 | `config/TaskConfiguration.java:18-24` | 使用，1 个 |
| `@EnableScheduling` | 调度开关 | `config/TaskConfiguration.java:10`、`HarnessRunnerCoreAutoConfiguration.java:34` | 使用，2 处 |
| `@Async` + `ThreadPoolTaskExecutor` | 注解式异步 | 无 | 未使用，生产直接起虚拟线程 |

`@EnableScheduling` 那两处值得注意：一处是宿主自己开的，另一处写在库的自动配置类上，宿主什么都没加就被动获得了调度能力，排查「定时任务不生效」时两个地方都要看。

## 自测三问

1. 把 `lab.scan-interval` 改成 `1s`，两个任务的线程名还不同吗。把 `taskScheduler` 的 `poolSize` 改成 1 再跑一次，慢任务会不会挡住扫描。
2. 注释掉 `@EnableAsync` 再跑，`writeAudit` 的线程名变成什么，为什么没报错。
3. 把 `scan` 里的 per-row try/catch 去掉，第 50 条抛异常之后本轮还会不会处理第 100 条。

## 行数说明

三个类合计八十九行（不含注释与空行），在纪律之内。
