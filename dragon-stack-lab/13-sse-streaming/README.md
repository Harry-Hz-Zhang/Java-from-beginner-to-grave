# 13 流式响应

## 演示什么

两种「边算边发」的响应：用 `SseEmitter` 把日志一条条推给浏览器，用 `StreamingResponseBody` 边读边写文件。
顺带演示三件线上必须做、demo 常常漏掉的事：显式声明响应字符集、发心跳、清理订阅者。

## 怎么运行

```bash
mvn -pl 13-sse-streaming spring-boot:run
```

服务监听 18091。另开一个终端。

```bash
curl --noproxy '*' -N -D - http://localhost:18091/api/v1/events
curl --noproxy '*' -D - -o trace.txt http://localhost:18091/api/v1/download
```

真实输出，注意第一行响应头。

```
HTTP/1.1 200
Content-Type: text/event-stream;charset=UTF-8
Transfer-Encoding: chunked

event:run.log
data:第 1 条日志，推送线程 sse-push

event:run.log
data:第 2 条日志，推送线程 sse-push

event:run.log
data:第 3 条日志，推送线程 sse-push

:heartbeat

:heartbeat

event:run.status
data:SUCCEEDED
```

下载接口的响应头与内容。

```
HTTP/1.1 200
Content-Disposition: attachment; filename="____.txt"; filename*=UTF-8''%E8%BD%A8%E8%BF%B9%E7%A4%BA%E4%BE%8B.txt
Content-Type: text/plain;charset=UTF-8
Content-Length: 48

第 0 行轨迹
第 1 行轨迹
第 2 行轨迹
```

服务端日志会打印订阅者的清理动作。

```
[订阅者] onCompletion，剩余 0
```

`curl` 的 `-N` 必须加，它关闭客户端缓冲，否则你会看到数据最后一次性打印，误以为没有流式效果。

## 关键语法点

第一，SSE 是浏览器原生支持的单向推送，比 WebSocket 简单，只需要一个普通 HTTP 响应，内容类型是 `text/event-stream`。

第二，`Content-Type` 必须显式带上 `charset=UTF-8`：用 `ResponseEntity` + `new MediaType(TEXT_EVENT_STREAM, UTF_8)` 返回 `SseEmitter`。只靠 `produces` 声明不够，`SseEmitter` 会给尚未设置的响应头填一个不带 charset 的默认值，浏览器按 Latin-1 解码，中文全是乱码。这是修过的真实线上问题，记录在 `dragon_harness/harness-runner/docs/VERIFICATION.md:32-34`，修复时专门补了覆盖中文与 emoji 的字符集测试。生产实现见 `controller/task/TaskEventStreams.java:25,71-73`。

第三，长连接必须发心跳。区间没有数据，Nginx、网关、负载均衡会按空闲超时掐断连接，客户端看到的是莫名断线。心跳是一个 comment 帧（`SseEmitter.event().comment(...)`），协议上以冒号开头，浏览器直接忽略、不触发任何回调，但连接一直有字节流动。生产 `TaskEventStreams.java:36-41` 在做这件事，间隔取自 `config/TaskProperties.java:35` 的 `eventHeartbeat`，默认 15 秒；本模块的 yaml 调成 1 秒便于观察。

第四，`onCompletion`、`onTimeout`、`onError` 三个回调必须注册，否则客户端断线后服务端集合里的 emitter 引用永远不释放，连接越多人越占内存。生产 `TaskEventStreams.java:31-33` 注册，`TaskEventHub.java:130,153-156` 在回调里把订阅者从 channel 上摘掉。

第五，`send` 只给事件名和数据，不设 `id`（`TaskEventStreams.java:67-69`），也没有 `Last-Event-ID` 重放。断线恢复靠服务端 replay 缓冲：每个 run 保留上限 1000 条、32MB 的事件，订阅队列 2048，队列满了投递一个流终止事件并结束订阅，客户端据此重新订阅，再由终态补偿拿到最终状态。见 `TaskEventHub.java:88-115`。

第六，结束不是发一个自定义的 `event: done`，而是推终态 `run.status` 然后关流。`done` 只能表达「我说完了」，表达不了「成功还是失败」，前端判断逻辑因此不同。

第七，`StreamingResponseBody` 解决的是「不能把整个文件读进内存」，你拿到 `OutputStream` 边读边写，内存占用只有缓冲区那么大。

第八，下载端点要四样东西：`ContentDisposition.attachment().filename(名字, UTF_8)`（中文文件名不加 UTF-8 会乱码）、`contentLength`（浏览器显示进度、判断是否下载完整）、`produces = MediaType.ALL_VALUE`（不预先锁定类型）、未知或非法类型兜底成 `application/octet-stream`。生产 `controller/task/TaskFileController.java:44-52,64-71` 就是这个写法。

第九，流式响应一旦开始写，响应头和状态码就固定了，此时抛异常无法回 500。客户端提前断开后再往响应里写会抛 `AsyncRequestNotUsableException`，不处理就会每个断开连接刷一屏异常。生产 `controller/GlobalExceptionHandler.java:76-78` 是一个方法体为空的专门 handler；注意它没有「响应是否已提交」的判断，因为能进到这个 handler 就说明响应已经不可用了。

第十，异步派发阶段会再次经过安全过滤链，所以 09 号模块里那行放行 `ASYNC` 是 SSE 能工作的前提。

## 完整用法清单

| 写法 | 说明 | 生产位置 | 生产是否使用 |
| --- | --- | --- | --- |
| `ResponseEntity<SseEmitter>` + `MediaType(TEXT_EVENT_STREAM, UTF_8)` | 显式声明字符集 | `TaskEventStreams.java:25,71-73` | 使用 |
| `SseEmitter.event().name(...).data(...)` | 事件名 + 数据 | `TaskEventStreams.java:67-69` | 使用 |
| `SseEmitter.event().id(...)` | 事件 id，配合 `Last-Event-ID` | 无 | 未使用 |
| `SseEmitter.event().comment(...)` | 心跳帧 | `TaskEventStreams.java:40` | 使用，间隔 15s |
| `onCompletion` / `onTimeout` / `onError` | 清理订阅者 | `TaskEventStreams.java:31-33` | 使用 |
| replay 缓冲 | 断线重连靠重放，不靠 id | `TaskEventHub.java:88-115` | 使用，1000 条 / 32MB |
| 订阅队列上限 | 满了结束订阅而非静默丢弃 | `TaskEventHub.java:277-288` | 使用，2048 |
| 终态 `run.status` + 关流 | 表示结束 | `TaskEventStreams.java:38-42` | 使用 |
| `StreamingResponseBody` | 边读边写大文件 | `TaskFileController.java:44-52` | 使用 |
| `ContentDisposition.attachment().filename(name, UTF_8)` | 中文文件名 | `TaskFileController.java:48-51` | 使用 |
| `contentLength` | 声明长度 | `TaskFileController.java:47` | 使用 |
| `produces = MediaType.ALL_VALUE` + octet-stream 兜底 | 类型不锁死 | `TaskFileController.java:44,64-71` | 使用 |
| `@ExceptionHandler(AsyncRequestNotUsableException.class)` | 断开后的空处理 | `GlobalExceptionHandler.java:76-78` | 使用 |
| `mvc.async.request-timeout: 0` | 异步请求不超时 | `harness_saas/application.yaml` | 使用 |

## 自测三问

1. 把 `contentType(EVENT_STREAM)` 换成只靠 `produces`，再用 `curl -D -` 看响应头，`charset` 还在吗。
2. 把 yaml 里的 `heartbeat` 改成 `300ms`，输出里 comment 帧的密度怎么变，为什么线上的值不能这么小。
3. 推送过程中按 Ctrl+C 中断 curl，看服务端日志打印的是哪个回调，订阅者数量有没有回到 0。

## 行数说明

两个类与一个异常处理器合计八十七行（不含注释与空行），在纪律之内。
