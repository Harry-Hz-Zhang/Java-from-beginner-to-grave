# 12 虚拟线程

## 演示什么

三种创建方式、五千个「阻塞五十毫秒」任务的并发密度、`ThreadLocal` 不继承、`synchronized` 与
`ReentrantLock` 的耗时对照，以及生产里唯一一处「平台线程更好」的地方。最后用一个极简接口
验证 `spring.threads.virtual.enabled` 这个开关到底有没有落地。

## 怎么运行

```bash
mvn -pl 12-virtual-threads spring-boot:run
```

服务监听 18092（`server.port` 被本机环境变量覆盖时以启动日志里的实际端口为准）。真实输出。

```
方式一，线程 = VirtualThread[#47,vt-single]/runnable@ForkJoinPool-1-worker-1
方式二，五千个阻塞五十毫秒的任务耗时 63 毫秒
方式三，线程 = VirtualThread[#5057]/runnable@ForkJoinPool-1-worker-2
子虚拟线程读到的追踪号 = null
main 自己的追踪号 = main 的追踪号
六十四任务：synchronized 内阻塞 1664 毫秒，ReentrantLock 内阻塞 211 毫秒
定时线程 = lab-timer-0，是否虚拟 = false
```

进程不退出，因为它是 Web 应用。按 Ctrl+C 关闭，会看到收尾的两行。

```
worker 收到 interrupt 后退出，线程 = vt-worker
@PreDestroy 已中断 worker，线程 = SpringApplicationShutdownHook
```

第二行的数字是关键：五千个任务各阻塞五十毫秒，总耗时只有六十五毫秒左右，说明它们在同时挂起，
而不是排队执行。第六行是 pinning 的对照：同样六十四个任务各阻塞两百毫秒，`synchronized` 要一秒六，
`ReentrantLock` 只要两百毫秒。

## 验证全局开关

```bash
curl --noproxy '*' http://localhost:18092/api/v1/thread
```

`application.yaml` 里 `spring.threads.virtual.enabled` 默认 `false`，返回

```
{"virtual":false,"thread":"Thread[#35,http-nio-18092-exec-1,5,main]"}
```

把这一行改成 `true` 重启，再 curl 一次，返回

```
{"virtual":true,"thread":"VirtualThread[#37,tomcat-handler-0]/runnable@ForkJoinPool-1-worker-6"}
```

返回值从 `false` 变 `true`，线程名从 `http-nio-18092-exec` 变成 `tomcat-handler`，开关才算真的生效。
配置写在 yaml 里不等于生效，中间可能被别的容器工厂或环境变量覆盖，这是唯一可靠的验证方式。

## 关键语法点

第一，虚拟线程解决的是「阻塞时不要占着操作系统线程」，它不会让单个任务变快。

第二，三种创建方式：`Thread.ofVirtual()` 适合精细控制单个线程，`newVirtualThreadPerTaskExecutor()` 是批量方式，`Thread.startVirtualThread()` 一行启动一个。三者底层是同一套 API，区别只在控制粒度。

第三，不要池化虚拟线程。创建成本极低，池化只会引入队列和拒绝策略这些不必要的复杂度。

第四，`try (var executor = ...)` 会自动等待所有任务结束，这是 Java 21 给 `ExecutorService` 加的能力。

第五，生产真正的写法是 `Thread.ofVirtual().name(...).unstarted(...)` 先拿句柄存进字段，再 `start`，并在 `@PreDestroy` 里 `interrupt`，容器关闭时能主动收尾。见 `RunnerImagePullApp.java:87,92-95`（它还在 `@Scheduled` 方法上加了 `synchronized` 防止重复起线程）、`SessionLogApp.java:118`（还挂了 `uncaughtExceptionHandler`）。主代码里没有一处 `newVirtualThreadPerTaskExecutor`，那是测试才用的。

第六，`ThreadLocal` 对每个虚拟线程各有一份副本，一万个虚拟线程就是一万份数据，存大对象会迅速吃光内存，所以优先用方法参数显式传递。它也不跨线程继承，这正是异步链路里必须显式传上下文的原因。要说明白：生产两个仓的主代码里没有任何自定义 `ThreadLocal`，`SecurityProjectContext` 也不存东西——它只读 `SecurityContextHolder` 并取出 `ProjectPrincipal`，带上下文靠的是显式传参，不是靠 ThreadLocal 跟过去。

第七，`synchronized` 块里阻塞会把载体线程钉住（pinning），虚拟线程退化成平台线程。JDK 21 仍有这个问题，替代方案是 `ReentrantLock`；生产规避的手段就是它（`RunnerImageApp.java:36`、`dragon_harness` 的 `ContainerApp.java:61`）。本模块的对照实验是实打实跑出来的，不是口头结论。JDK 24 起这个限制已经解除，但生产跑的是 21。

第八，有些线程不该虚拟化。定时器线程大部分时间在排队等触发，阻塞极短，虚拟线程没有收益，而 `ScheduledThreadPoolExecutor` 需要一个稳定的平台线程守时，还需要守护属性保证它不阻止 JVM 退出。生产 `JsonLineRpcConnection.java:73` 用的就是 `Thread.ofPlatform().daemon().name("dsh-rpc-timeout-", 0).factory()`。这一条打破了「虚拟线程全面更好」的单向叙事。

## 完整用法清单

| 写法 | 说明 | 生产位置 | 生产是否使用 |
| --- | --- | --- | --- |
| `Thread.ofVirtual().name(...).start(...)` | 起一个带名字的虚拟线程 | `TaskRunExecutor.java:94`、`TaskEventStreams.java:34` | 使用 |
| `...unstarted(...)` + 持有句柄 + `@PreDestroy` 中断 | 长任务可收尾 | `RunnerImagePullApp.java:87,92-95` | 使用 |
| `uncaughtExceptionHandler` | 线程异常时收口 | `SessionLogApp.java:118` | 使用 |
| `Thread.startVirtualThread(...)` | 一行启动，无句柄 | 无 | 未使用 |
| `Executors.newVirtualThreadPerTaskExecutor()` | 批量起线程 | 仅测试 `LocalTaskFilesTests.java:178` | 主代码未使用 |
| `Thread.ofPlatform().daemon().name(...,0).factory()` | 平台线程工厂给定时器用 | `JsonLineRpcConnection.java:73` | 使用 |
| `ReentrantLock` 替代 `synchronized` | 避免 pinning | `RunnerImageApp.java:36`、`ContainerApp.java:61` | 使用 |
| `spring.threads.virtual.enabled` | 全局开关，Tomcat 请求走虚拟线程 | `harness_saas/src/main/resources/application.yaml:8-10` | 使用，值为 true |
| 自定义 `ThreadLocal` 传上下文 | 异步链路隐式传递 | 无 | 未使用，靠显式传参 |

## 自测三问

1. 把 `spring.threads.virtual.enabled` 改成 `true` 重启，curl 返回的 `virtual` 字段和线程名前缀各变成了什么。
2. 把 `TASKS` 从 64 改成 8 再跑，两种锁的耗时还差得多吗，为什么（提示：载体线程数等于核数）。
3. 把 `timerDemo` 的线程工厂换成 `Thread.ofVirtual().name("lab-timer-", 0).factory()`，会出现什么现象，为什么定时器不该这么写。

## 行数说明

两个类合计九十行（不含注释与空行），在纪律之内。
