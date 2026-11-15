# 17 JSON-RPC over stdio

## 演示什么

父进程启动子进程，两者不用端口、不用网络，只靠标准输入和标准输出喊话。消息格式是 JSON-RPC 2.0，帧边界是换行符。这是 runner 与容器内 Agent 通信的协议。

本模块覆盖六件事：正常 `result`、错误帧 `error`、每请求超时、通知回调、服务端反向请求、stderr 环形缓冲。

## 怎么运行

```bash
mvn -pl 17-jsonrpc-stdio spring-boot:run
```

真实输出。

```
[notification] server/ready {"version":"demo-1.0"}
第一种调用 = pong
第二种调用 = prepared:world
错误帧     = code=-32601，Method not found: shell/exec
超时分支   = code=-32000，TIMEOUT: request timed out; peer work may still be running
[client] 收到服务端反向请求 server/hello，回复 -32601
反向请求   = 已发出一次反向请求
stderr 尾部 = 43 字节，只在内存里
客户端已关闭，子进程被销毁
```

第二行来自服务端启动时主动发的通知（没有 `id`），它在任何请求之前就到了，说明通知走的是回调通道，不占 `pending` 表。

## 关键语法点

**第一，为什么走 stdio 而不是端口。** 容器内进程没有对外暴露端口，唯一能稳定连上的就是它的 stdin 和 stdout。这条路不需要网络配置，代价是不能多路复用——所有消息挤在一条流里，所以必须约定「一行一个 JSON」，用换行符切帧。

**第二，`id` 与 `pending` 表。** 一条连接上可能同时有多个请求在飞，响应顺序不保证和请求一致，只能靠 `id` 对应。本模块用 `"req_" + UUID`（生产 `JsonLineRpcConnection:95` 完全一致），服务端主动发来的请求则可能带任意 `id`，所以不能假设「所有带我认识的 id 的帧都是我的响应」，要先判断 `method` 字段是否存在。

**第三，错误帧必须解析出 `code`。** 服务端对未知方法回 `{"code":-32601,"message":"..."}`。如果客户端只读 `result` 字段，收到 error 帧就会 NPE。生产 `:231-246` 会取出 `error.code`、`message`、`data` 并包成带 code 的异常；本模块的 `RpcException.code()` 就是那个 wire code。

**第四，每请求超时靠自建计时线程池。** `ScheduledThreadPoolExecutor(1, daemon "dsh-rpc-timeout-")`，并设 `setRemoveOnCancelPolicy(true)`，否则取消掉的定时任务会一直堆在队列里（生产 `:69-74`）。超时到点后做的不只是 `completeExceptionally`，还要摘 `pending`、释放名额，所以不能简单用 `orTimeout`。

**第五，超时只放弃本地关联，对方的工作仍在跑。** 协议里没有「取消对方操作」的方法（生产 `:110-111`）。本 demo 里 `slow` 方法要 1.5 秒，客户端 300 毫秒就超时；服务端稍后仍会写回一个响应，客户端因为 `pending` 里已经没有这个 id 而忽略它。这不是 bug。

**第六，通知与服务端反向请求要分开处理。** 无 `id` 的行是通知，交给回调；有 `id` 但不是自己发出的请求，说明是服务端反向请求，必须回 `-32601 Server-to-client requests are not supported`，而不是静默丢弃（生产 `:211-222`）。静默丢弃会让对端永远等结果。

**第七，帧上限与半帧。** 生产不用 `BufferedReader.readLine()`，而是逐字节扫 `\n`（`:165-183`）：`readLine` 没有长度上限，对端发一个超长行就能把内存吃光；而且它会把「末尾没有换行的半帧」当成完整一帧返回。生产在 EOF 时剩下的半帧直接丢弃，超过 `maxFrameBytes` 抛 `PROTOCOL_ERROR`。

**第八，stderr 必须读，但绝不打印。** 两个理由：不读的话子进程写满 stderr 管道会阻塞；而内容里可能有凭证，所以只保留尾部 8KB 在内存里供诊断（生产 `:39`、`:249-265`）。**生产不把 stderr 转发到日志**，本 demo 也只在最后打印字节数，不打印内容。

**第九，关闭是单向的。** 先让所有 `pending` 以 `TRANSPORT_CLOSED` 失败、`timers.shutdownNow()`，再 `destroy → waitFor(1s) → destroyForcibly`，最后按顺序关流（生产 `:289-313`）。生产没有自动重连，连接断开后只能显式 restart（`ContainerApp:130-132`）。

**第十，协议用的 `JsonMapper` 必须独立创建。** 不能跟着宿主的 `spring.jackson.*` 配置走：宿主若把 Jackson 配成缩进输出，一个请求就变成多行，对端 `readLine` 只读到第一行，协议立刻失效，且表现为偶发解析错误。

## 完整用法清单

| 写法 | 作用 | 生产项目里的位置 |
| --- | --- | --- |
| 一行一帧（`write` + `'\n'` + `flush`） | stdio 上的帧边界 | `JsonLineRpcConnection.writeFrames()` |
| 逐字节扫 `\n` + `maxFrameBytes` | 有上限地切帧，半帧丢弃 | `:165-183` |
| `"req_" + UUID` | 全连接唯一的请求 id | `:95` |
| `ConcurrentHashMap<String, Pending>` | 按 id 关联响应与等待者 | `:45` |
| `ScheduledThreadPoolExecutor(1, daemon, "dsh-rpc-timeout-")` + `setRemoveOnCancelPolicy(true)` | 每请求超时 | `:69-74`、`:108-114` |
| `error.code` / `message` / `data` | 错误帧解析，抛带 code 的异常 | `:231-246` |
| 无 `id` → 通知回调 | 服务端推送事件 | `:218-222` |
| 有 `id` 但非本地发出 → 回 `-32601` | 拒绝反向请求 | `:211-217` |
| stderr 尾部 8KB 环形缓冲，只留在内存 | 诊断而不泄漏凭证 | `:39`、`:249-265` |
| `AutoCloseable`：pending 失败 → 停 timer → destroy 三级 → 关流 | 保证子进程与 fd 回收 | `:289-313` |
| 独立 `JsonMapper` | 隔离宿主 Jackson 配置 | `HarnessRunnerCoreAutoConfiguration` 的 `PROTOCOL_JSON` |

## 自测三问

**问一**：客户端只读 `result` 字段、不判 `error`，遇到未知方法时现象是什么？

答：解析出来的是一个没有 `result` 的帧，`get("result")` 返回 `null`，再调 `asText()` 直接 NPE。更糟的是这个 NPE 出现在读线程里，会把整条连接带走，调用方看到的是「连接莫名断了」，而不是「方法不存在」。

**问二**：为什么超时之后不能把那个 `id` 从 `pending` 里删掉就完事？

答：还得释放并发名额、取消定时器，并且让 `future` 以 `TIMEOUT` 结束。三项漏一项的表现分别是：并发名额泄漏（跑久了再也发不出请求）、定时器堆积（内存缓慢增长）、调用方永远阻塞（最严重）。所以生产用统一的 `settle` 收口。

**问三**：stderr 不读会怎样？读了之后为什么不能直接写日志？

答：不读的话，子进程写满管道缓冲区后阻塞在 `write`，整个 RPC 连接卡死，而且从 stdout 侧完全看不出原因。读了之后不能写日志，是因为容器进程启动时可能把凭证、token 打进 stderr，日志会被长期保存和转发，属于信息泄漏。生产只把尾部留在内存里，供异常时生成诊断信息。

## 对应生产项目的位置

| 这里的概念 | 生产项目的用法 |
| --- | --- |
| 读循环 + pending + 超时 | `harness-runner-core/.../infrastructure/rpc/JsonLineRpcConnection`（客户端侧连接对象） |
| 启动子进程 | `JsonLineRpcConnectionFactory` + `DockerCliContainerGateway.ProcessChannel`（不是 `StdioRpcTransport`，那个类不存在） |
| 容器内服务端 | DSH 自己的实现，不是本模块这个 `RpcServer` |
| 协议 JSON 实例 | `HarnessRunnerCoreAutoConfiguration` 里的 `static final JsonMapper` |
| 容器 stdio | `docker start --attach --interactive`，由 `ProcessChannel` 包装 |

## 行数说明

三个类合计约三百一十行代码（不含注释）。`StdioRpcClient` 单独一百六十行左右，因为六件事（写帧、读帧、错误帧、通知、反向请求、stderr）各自都是独立分支，删掉任何一段都会让某条协议路径失去覆盖。`RpcServer` 是个独立进程的 `main`，不能与客户端合并，否则演示不了两个进程。
