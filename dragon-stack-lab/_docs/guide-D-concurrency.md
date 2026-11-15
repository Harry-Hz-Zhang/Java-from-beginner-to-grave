# D 组讲解：并发与流式

这一组四个模块（11 到 14）讲的是同一件事的四个面：**代码不再是一条直线跑到底**。任务在后台定时跑、请求被丢到别的线程、结果边算边推给前端、跨进程共享状态放在 Redis。

初学者最容易在这里写出「能跑但会出事」的代码，因为这些错误几乎不会在小流量下暴露。

## 一、先建立一个判断框架

面对「这件事不该阻塞当前请求」的需求，有四条路，选错任何一条都会留下隐患。

第一条，**按时间反复做**。用 `@Scheduled`。适用于扫描补偿、状态同步、清理过期数据这类不需要人触发的事。

第二条，**这一次交给别人做，我不等**。用 `@Async`。适用于发通知、写审计日志、触发下游这类「发起方不需要结果」的事。

第三条，**结果要一点一点给出去**。用 SSE 或 `StreamingResponseBody`。适用于大模型输出、日志跟随、大文件导出。

第四条，**多个线程/多个实例要看到同一份状态**。用 Redis。适用于会话、计数、缓存。

四条路的边界其实很清楚：**时间是触发源用第一条，调用方不等结果用第二条，结果要连续输出用第三条，状态要跨进程用第四条。** 反过来，需要返回结果给调用方却用了 `@Async`，就是这一组最典型的错误。

还有一条横向的判断：**这段代码在哪个线程上跑**。这一组所有的坑，本质上都是这个问题没想清楚。

## 二、串讲：一次任务执行的线程轨迹

用户点「运行任务」，请求打到控制器，跑在 Tomcat 的工作线程上，名字类似 `http-nio-8080-exec-3`。

控制器把任务状态改成 `QUEUED`，落库，返回 202。请求结束，这个 Tomcat 线程被回收去处理下一个请求。

接下来是定时任务。六十秒后，`TaskRunExecutor` 的 `@Scheduled(fixedDelay = 60000)` 触发，跑在 `scheduling-1` 线程上。它扫出 `QUEUED` 的任务，对每一个调 `@Async("taskExecutor")` 的方法。注意，如果它调的是自己类里的方法（`this.xxx()`），代理被绕过，异步完全不生效，任务会在 `scheduling-1` 上串行执行完——一个卡住，所有任务一起卡。

真正执行时，`taskExecutor` 池里分配一个线程，比如 `task-2`。它启动容器、建立 stdio 连接、发 JSON-RPC 请求。这里如果用的是虚拟线程，容器数量可以开到几千个，因为虚拟线程在等待 IO 时会让出载体线程，不占着操作系统线程不放。

执行过程中，Agent 不断往 stdout 写日志。这些日志要推给前端，走 SSE。每个 SSE 连接持有一个 `SseEmitter`，推送动作发生在 `task-2` 线程上，但写入的是 Tomcat 的异步响应，不占用 `task-2` 的栈去等待网络发送。

同时，另一个请求可能在查这个任务的日志。它先查 Redis 缓存，命中就直接返回，不查库。

整条链路上出现了五个线程：`http-nio-*-exec-*`、`scheduling-1`、`task-*`、虚拟线程、以及 Redis 客户端自己的 IO 线程。**每个 `@Async`、`@Scheduled`、`Thread.ofVirtual()`、以及每一次跨进程调用，都是一次线程切换。** 任何一处以为「还是原来那个线程」，就会踩到本组的坑。

## 三、完整用法清单

### @Scheduled 的参数

| 参数 | 含义 | 什么时候用 |
| --- | --- | --- |
| `fixedDelay` | 上次**执行完**再等 N 毫秒 | 扫描类任务，生产首选 |
| `fixedRate` | 每 N 毫秒触发，不管上次是否跑完 | 心跳、指标上报 |
| `initialDelay` | 首次延迟 | 几乎总要写，等依赖就绪 |
| `cron` | 日历表达式 | 每天几点这种需求 |
| `fixedDelayString` | 可从配置读的字符串 | `${harness.image.sync-interval:10m}` |
| `zone` | cron 的时区 | 跨时区部署时必写 |

`fixedDelay` 和 `fixedRate` 的区别是这一组最容易选错的地方。扫描任务用 `fixedRate`，一旦某次执行超过周期，就会有两个扫描同时跑，同一条数据被处理两次。

### @Async 的约束

| 约束 | 说明 |
| --- | --- |
| 必须经由代理调用 | 同类 `this` 调用退化成同步 |
| 方法必须 `public` | 私有方法代理拦不住 |
| 返回值 | `void`、`Future`、`CompletableFuture` |
| 指定执行器 | `@Async("beanName")`，不写用默认的 |
| `@EnableAsync` | 必须开，否则注解静默失效 |
| 异常 | `void` 方法的异常无人接收，需配 `AsyncUncaughtExceptionHandler` |

### 线程池的四个参数

| 参数 | 作用 | 设错的后果 |
| --- | --- | --- |
| 核心线程数 | 常驻线程 | 太小，任务排队 |
| 最大线程数 | 峰值上限 | 太大，上下文切换开销 |
| 队列容量 | 排队上限 | 无限队列等于最大线程数失效 |
| 线程名前缀 | 排查用 | 不设，日志里分不清谁是谁 |

### 虚拟线程

| 写法 | 用途 |
| --- | --- |
| `Thread.ofVirtual().name("x").start(r)` | 单个虚拟线程 |
| `Executors.newVirtualThreadPerTaskExecutor()` | 每任务一个虚拟线程，最常用 |
| `Thread.startVirtualThread(r)` | 简写形式 |
| `spring.threads.virtual.enabled=true` | 让 Tomcat 用虚拟线程处理请求 |
| `Thread.ofVirtual().factory()` | 拿 ThreadFactory |

三个必须知道的限制：`ThreadLocal` 不自动继承到子虚拟线程（本 demo 验证了这点，子线程读到 `null`）；`synchronized` 块里的阻塞会 pin 住载体线程，应改用 `ReentrantLock`；虚拟线程不是「更快」，是「更省」，它省的是线程栈内存和上下文切换，不省 CPU。

### Redis 的三种用法

| 用法 | 命令 | 要点 |
| --- | --- | --- |
| 带过期的会话 | `set(k, v, ttl)` | 一条原子命令，别拆成 set + expire |
| 计数限流 | `increment(k)` | 返回自增值，再决定是否设过期 |
| 读穿缓存 | `get` → 未命中回源 → `set(ttl)` | 注意穿透与并发回源 |
| 分布式锁 | `setIfAbsent(k, v, ttl)` | 释放要用 Lua 比对持有者 |

### 流式响应的两种方式

| 方式 | 适用 | 关键配置 |
| --- | --- | --- |
| `SseEmitter` | 服务端推送事件，浏览器 `EventSource` 直接连 | `SseEmitter(0L)` 表示不超时 |
| `StreamingResponseBody` | 下载、任意二进制流 | `Content-Disposition` 设文件名 |
| — | 两者都必须关掉异步超时 | `spring.mvc.async.request-timeout: 0` |

## 四、八个必须知道的坑

第一个，**同类调用绕过代理**。`@Async` 和 `@Transactional` 都是这个毛病。症状是「注解写了但完全没效果」，而且不报错。判断方法很简单：打一行线程名，看是不是同一个线程。

第二个，**非 Web 应用里调度线程不退出**。`@Scheduled` 的线程是常驻的，`main` 方法执行完 JVM 也不会退。本 demo 用 `System.exit(SpringApplication.exit(context))` 主动退出。生产项目是 Web 应用，没有这个问题。

第三个，**`fixedRate` 会重叠执行**。任务耗时超过周期时，两次执行同时跑。扫描类任务用这个会导致数据被重复处理。

第四个，**默认调度器只有一个线程**。五个 `@Scheduled` 方法共用它，一个卡住全部延迟。需要并行要自己配 `ThreadPoolTaskScheduler`。

第五个，**`ThreadLocal` 在虚拟线程里丢失**。本 demo 验证了：父线程设了追踪号，子虚拟线程读到 `null`。跨线程传递必须用参数显式传，或者用 `InheritableThreadLocal`（但仍不适用于线程池场景）。

第六个，**`synchronized` 里的阻塞会 pin 住载体线程**。虚拟线程遇到 `synchronized` 内的阻塞操作时无法让出载体，退化成平台线程。JDK 21 里这个限制仍然存在，改用 `ReentrantLock` 可以避免。

第七个，**SSE 不关超时就会被切断**。`spring.mvc.async.request-timeout` 默认值会让长连接在一段时间后断开，前端看到的是「连着连着就没了」。`SseEmitter(0L)` 加上配置里的 `0` 才是真正无限。

第八个，**`INCR` + `EXPIRE` 不是原子的**。两条命令之间崩溃，计数器就永不过期，这个 key 永久占着内存且值一直累加。生产要用 Lua 脚本或者 `SET NX EX` 先占位。

## 五、自测题

1. `@Scheduled(fixedDelay = 1000)` 和 `fixedRate = 1000` 在任务耗时 3 秒时分别会怎样？
2. 为什么 `@Async` 方法被同类调用时不生效？Spring 用了什么机制？
3. 线程池的队列设成无限大，`maximumPoolSize` 还有意义吗？
4. 虚拟线程在什么情况下会被 pin 住？怎么避免？
5. `ThreadLocal` 的数据在切换到虚拟线程后还能读到吗？为什么？
6. `SseEmitter` 和 `StreamingResponseBody` 各自的适用场景是什么？
7. SSE 连接「连着连着就断了」，第一个要查什么配置？
8. 用 Redis 做限流时，`INCR` 之后进程崩溃会留下什么问题？
9. Cache-Aside 模式的「缓存穿透」和「并发回源」分别是什么？各怎么解决？
10. `spring.threads.virtual.enabled=true` 影响的是哪一层？它会让数据库连接池也变大吗？

## 六、学完这一组之后

你应该能在写任何一行异步代码之前，先回答「这段跑在哪个线程上」和「它失败了谁负责」这两个问题。

也应该能判断：这个需求该用定时、该用异步、该用流式、还是该用 Redis，而不是一律 `@Async` 了事。

下一组进 E 组。D 组讨论的是「同一进程内的多个线程」，E 组要跳出进程：库怎么被别人的应用加载、外部命令怎么调、怎么和另一个进程通信、多个异步结果怎么合并。
