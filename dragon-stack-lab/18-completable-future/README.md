# 18 异步结果与 CompletableFuture

## 演示什么

两件事，顺序不能颠倒。

第一，**生产真实形态**：手建 `CompletableFuture`，用虚拟线程去完成它，用 `whenComplete` 把取消传成中断，用 `Semaphore` 和 `ArrayBlockingQueue` 做背压。

第二，**五种组合 API 的对照参考**：`thenCombine`、`exceptionally`、`orTimeout`、`allOf`，以及 `supplyAsync` 本身。这些 API 值得学，但生产一处都没用——每一段的注释和输出都标了「生产未使用」以及它替代的是哪个手写环节。

## 怎么运行

```bash
mvn -pl 18-completable-future spring-boot:run
```

真实输出。

```
[生产范式] 手建 future = 最终答复，完成于 sdk-run-demo，等待线程 main
           回调线程 = harness-client-completion
[回调线程] future 在 dsh-rpc-stdout 完成，回调被第二个参数换到了另一个线程
[早失败] 参数非法 = INVALID_ARGUMENT
[早失败] 已关闭   = TRANSPORT_CLOSED
[早失败] 限额已满 = CONFLICT
[取消传播] 已取消 = true，上游收到中断并重新置位 = true，worker 已结束 = true
[背压] 队列容量 2 提交 5 个，拒绝 3；并发名额 2 申请 3 个，拿到 2 → 拒绝的立刻回 CONFLICT
[API 参考] thenCombine = 镜像元数据 + 任务配置（生产未使用）
[API 参考] exceptionally = 降级值（镜像拉取失败）（生产未使用）
[API 参考] orTimeout = TimeoutException（生产改用自建 timer 池）
[API 参考] allOf = [工作区, 密钥]（生产未使用，create 是同步顺序执行）
```

第一行的 `完成于 sdk-run-demo` 和 `等待线程 main` 是两个不同的线程，这是本模块最该看的一行：`future.get()` 阻塞的是调用者，完成 future 的是另一个线程。

## 生产真实形态：手建 + 虚拟线程

生产里一次 run 的骨架是（`SdkApp.java:64-66`）：

```java
var result = new CompletableFuture<RunResult>();
Thread worker = Thread.ofVirtual().name("sdk-run-" + sessionId).unstarted(() -> {
    try { result.complete(collect(...)); }
    catch (InterruptedException error) { Thread.currentThread().interrupt(); result.completeExceptionally(...); }
    catch (Throwable error) { result.completeExceptionally(error); }
});
result.whenComplete((value, error) -> { if (result.isCancelled()) worker.interrupt(); });
worker.start();
return result;
```

四个动作缺一不可：**先建 future 再启动线程**，这样调用方立刻拿到句柄；**`unstarted` 之后手动 start**，是为了在启动前挂上 `whenComplete`；**`completeExceptionally`** 保证异常也走 future；**`whenComplete` 里判 `isCancelled`** 才能把取消变成中断。

不用 `supplyAsync` 的原因很直接：`supplyAsync(fn)` 的「返回 future」和「开始执行」是同一个动作，没法做到「先返回、后决定在哪个线程跑、取消时中断谁」。

## 关键语法点

**第一，`whenCompleteAsync` 的第二个参数决定回调线程。** 不传时回调用 `ForkJoinPool.commonPool()`；传了就在指定的执行器上跑。生产刻意传自己的执行器（`CoreTranslation.java:49-52、75-79`），让 client 侧的 future 在独立的虚拟线程完成——因为 Core 的 future 是在 runtime 唯一的 stdout reader 上完成的，回调一旦阻塞在那里，整条协议流就停了。

**第二，异步 API 的校验失败也走 future。** 参数非法、连接已关闭、限额已满，生产返回 `CompletableFuture.failedFuture(...)` 而不是 `throw`（`JsonLineRpcConnection.java:87、90、92`）。理由是调用方只想写一套错误处理：`future` 的正常路径和失败路径。如果一半 `throw` 一半 future 失败，每个调用点都得包两层 `try`。

**第三，`cancel(true)` 不会自己中断谁。** 它只把 future 置为取消态。要真的停下上游，得在 `whenComplete` 里检查 `isCancelled()` 再 `interrupt()`；上游在 `InterruptedException` 里再 `cancel(true)` 一次并 `Thread.currentThread().interrupt()` 恢复中断位（`CoreTranslation.java:80、85-97`，`ContainerApp.java:421-426`）。不恢复中断位，外层再也不会知道「这次是被取消的」。

**第四，`orTimeout` 只是让 future 早结束，不取消任务。** 底层线程照跑。生产等 RPC 响应时不用它，而是用 `ScheduledThreadPoolExecutor(1, daemon, "dsh-rpc-timeout-")` 加 `setRemoveOnCancelPolicy(true)`（`JsonLineRpcConnection.java:72-74、108-114`），因为超时那一刻还要摘 `pending`、释放名额，光让 future 失败是够不着的。

**第五，`exceptionally` 只兜上游。** 它处理写它之前的阶段抛出的异常，写在它之后的操作管不了。生产没有用它兜镜像拉取，因为 `ContainerApp.create` 是同步顺序执行（`ContainerApp.java:80-120`），失败就是直接抛异常，不存在「异步降级」这个环节。

**第六，`allOf` 不返回结果，也不取消其他任务。** 任一失败它立刻异常结束，其余任务继续跑。本 demo 只用来演示 API 本身；生产的多任务场景是「有界并发 + 满了拒绝」，不是「全部一起上」。

**第七，背压比 `allOf` 更贴近真实需求。** `Semaphore.tryAcquire()` 拿不到名额、`ArrayBlockingQueue.offer()` 放不进队列，都立刻返回 `CONFLICT`，调用方马上知道「系统忙」，而不是排一个看不见的队（`JsonLineRpcConnection.java:69-71、91-94、115-117`）。本 demo 的 `[背压]` 一行就是这两个动作。

## 完整用法清单

| 写法 | 作用 | 生产项目里的位置 |
| --- | --- | --- |
| `new CompletableFuture<T>()` + 虚拟线程 `complete` | 先返回句柄、后决定执行线程 | `SdkApp.java:64-66`、`CoreTranslation.java:72-82` |
| `completeExceptionally(t)` | 异常也走 future | 同上 |
| `whenComplete((v, e) -> ...)` + `isCancelled()` | 把取消转成中断 | `SdkApp.java:74`、`CoreTranslation.java:80` |
| `cancel(true)` + 上游 `interrupt()` | 取消传播 | `CoreTranslation.java:85-97` |
| `whenCompleteAsync(fn, executor)` | 把回调换到指定线程 | `CoreTranslation.java:75-79` |
| `failedFuture(...)` | 参数非法 / 已关闭 / 限额已满的早失败 | `JsonLineRpcConnection.java:87、90、92` |
| `Semaphore.tryAcquire()` + `offer()` | 有界并发与队列，满了回 `CONFLICT` | `JsonLineRpcConnection.java:69-71、91-94、115-117` |
| `ScheduledThreadPoolExecutor(1, daemon)` + `setRemoveOnCancelPolicy(true)` | 每请求超时的自建计时器 | `JsonLineRpcConnection.java:72-74、108-114` |
| `join()` / `get()` | 取值，`join` 不抛受检异常 | `ContainerApp.java:421-426` |
| `supplyAsync(fn, pool)` | **生产未使用**，仅作 API 参考 | 无 |
| `thenCombine(other, fn)` | **生产未使用**，仅作 API 参考 | 无 |
| `thenCompose(fn)` | **生产未使用**，仅作 API 参考 | 无 |
| `thenApply(fn)` | **生产未使用**，仅作 API 参考 | 无 |
| `exceptionally(fn)` | **生产未使用**，仅作 API 参考 | 无 |
| `orTimeout(t, unit)` | **生产未使用**，生产用自建 timer 池 | 无 |
| `allOf(futures)` | **生产未使用**，仅作 API 参考 | 无 |
| `anyOf(futures)` | **生产未使用** | 无 |
| `runAsync(fn, pool)` | **生产未使用** | 无 |
| `handle((r, ex) -> ...)` | **生产未使用** | 无 |

## 生产为什么不用 `CompletableFuture` 的组合方法

在 `harness-runner/*/src/main` 与 `harness_saas/src/main` 里 grep `supplyAsync|thenCombine|thenCompose|thenApply|allOf|anyOf|exceptionally|orTimeout|runAsync`，命中数为 **0**。原因有三条。

**其一，执行单元是「每任务一个虚拟线程」，不是线程池。** 组合方法的价值在于「把任务丢进池里编排」。生产每个 run 一个虚拟线程，池的调度问题（核心数、队列、拒绝策略）全都不存在，`supplyAsync` 那套基础设施用不上。

**其二，需要精确控制完成线程。** Core 的 future 在 stdout reader 上完成，client 的 future 必须换到自己的虚拟线程（`CoreTranslation.java:49-52`）。组合方法的默认完成语义是「在哪个线程完成就在哪继续」，要改就得每段都传执行器，反而比手写更啰嗦。

**其三，需要「取消 = 中断 = 释放名额」这条完整链路。** `cancel(true)` 本身什么都不做，`whenComplete` 里判 `isCancelled`、`interrupt`、`finally` 里 `slots.release()`，这三步是组合方法表达不了的。生产的 `SdkApp.run` 就是靠这条链路同时做到「调用方可以取消」和「名额一定归还」。

所以本模块的正确读法是：**先看第一段的生产范式，再看后面五段 API 时始终记得它们在生产里没有对应位置。**

## 自测三问

**问一**：`future.get()` 阻塞的是哪个线程？`future.complete()` 又是在哪个线程执行的？

答：`get()` 阻塞的是调用者（本 demo 的 `main`）；`complete()` 在虚拟线程 `sdk-run-demo` 上执行。这正是「异步」的含义：调用者阻塞等待，执行者在别处干活，两者通过 future 交接结果。

**问二**：`cancel(true)` 之后，那个还在 `Thread.sleep` 的虚拟线程会自己停下来吗？

答：不会。`cancel(true)` 只把 future 置为取消态。必须有人在 `whenComplete` 里看到 `isCancelled()` 并调用 `worker.interrupt()`，`sleep` 才会抛 `InterruptedException`。本 demo 的 `[取消传播]` 三项全是 `true`，说明这条链路是通的；把 `whenComplete` 那三行删掉，`worker 已结束` 就会变成 `false`。

**问三**：为什么限额满的时候返回 `failedFuture(CONFLICT)` 而不是抛异常？

答：因为调用方拿到的永远是一个 future。抛异常和 future 失败混用，会让每个调用点都必须写「先 try 再 catch ExecutionException」两套逻辑，而且一处在异步、一处在同步，排查时很难判断错误从哪来。统一成 future 失败，错误处理只有一条路径。

## 行数说明

单类约一百五十行代码（不含注释）。六段演示各自独立，每段都要建 future、等结果、打印，最少的也有七行。合并会牺牲「一段演示一件事」的清晰度，尤其是第一段（生产范式）和最后一段（API 参考）必须明确分开，否则读者会把「生产在用」和「仅作参考」混为一谈。
