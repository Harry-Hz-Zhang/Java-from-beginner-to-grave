# 21 对接外部系统

## 演示什么

用 JDK 自带的 `HttpClient` 对接 Docker Registry V2 协议，不引 SDK。同时用 JDK 自带的 `HttpServer` 造一个假仓库和一个假对象存储，让四条路径都能跑通：换票后成功、401 换 token 重放、404 分层、blob 重定向到对象存储且不带凭据。

## 怎么运行

```bash
mvn -pl 21-external-sdk spring-boot:run
```

真实输出。

```
鉴权后 schemaVersion = 2
回源收到的凭据     = {"authorization":"null"}
失败分支 = REPOSITORY_NOT_FOUND 镜像仓库不存在或该标签不存在
假服务已关闭
```

假仓库跑在 `127.0.0.1:18092`，假对象存储跑在 `18093`，随应用启动、随应用关闭。第二行的 `null` 是重点：重定向到了另一台主机，令牌没有跟着过去。

## 为什么不用 SDK

镜像仓库的接口就是普通 HTTPS 加几个约定的路径和 `Accept` 头。这种场景引入 SDK 的代价大于收益：多一个依赖、多一套版本兼容问题、出问题时还要去读它的内部实现。判断标准是：**协议简单到能用一百行代码写清楚，就不要引依赖。**

生产项目的 `DockerRegistryClient` 就是手写的，只用 JDK 的 `HttpClient`。反过来，对象存储的签名算法、支付的回调验签，那种就该用官方 SDK——见文末「什么时候该引 SDK」。

## 关键语法点

**第一，三个超时各有分工，值取自生产配置。**

| 位置 | 管的是 | 生产的值 | 绑定来源 |
| --- | --- | --- | --- |
| `HttpClient.connectTimeout` | TCP 握手最多等多久，管「连不上」 | 5 秒 | `harness.image.connect-timeout` |
| `HttpRequest.timeout` | 发请求到收响应头，管「连上了但不回」 | 30 秒 | `harness.image.read-timeout` |
| 业务层总时长 | 整次镜像同步的总预算 | 30 分钟 | `harness.image.pull-timeout` |

前两个都是 `Duration` 字段，写在 `RunnerImageProperties` 这个 `@ConfigurationProperties` 记录上，用 `@DefaultValue("5s")`、`@DefaultValue("30s")` 给默认值，构造器里校验必须在 1ms 到 5m 之间。第三个 `pullTimeout` 校验的是 1m 到 2h，它不属于单次 HTTP 请求，所以客户端内部用不到。

只设 `connectTimeout` 是最常见的错：服务器接受了连接但迟迟不返回响应时，线程会一直挂着，而它看起来像是「连接是通的」。

**第二，`Accept` 头必须显式设置。** Registry 的清单接口会返回多种格式（OCI 索引、Docker V2 清单、旧版 V1 清单），服务端按 `Accept` 决定返回哪一种。生产项目把这四种类型拼成一个头一次发出去。

**第三，401 不是终点，是换票的起点。** 私有仓库对匿名请求回 401，并在 `WWW-Authenticate` 里写明 token 端点的 `realm`、`service`、`scope`。正确流程是：

1. 收到 401，读 `WWW-Authenticate`；
2. 带 Basic 凭据请求 token 端点，拿到 `token`；
3. 用 `Authorization: Bearer <token>` 把原请求重放**一次**。

只重放一次，因为第二次还 401 说明凭据本身有问题，继续重试只是把一次失败放大成一轮失败。生产项目的 `authenticate()` 与 `request()` 都是这个模式，后者还负责「同步到一半 token 过期了就换一张再试一次」。

**第四，状态码要分层成领域异常，而不是一律 `IllegalStateException`。** 本模块定义了 `RegistryException`，带三个原因：

- `AUTHENTICATION_FAILED`（401/403）：检查用户名、密码、仓库地址，重试无用；
- `REPOSITORY_NOT_FOUND`（404）：仓库或标签不存在，重试无用；
- `UNAVAILABLE`（5xx、连不上、超时、响应格式异常）：这一类才值得重试。

上层按 `reason()` 分流，不需要解析 message 文本。生产项目同样有一层 `ImageRegistryException.Reason`。

**第五，响应体必须有上限。** 清单是 JSON，正常只有几 KB。设 `MAX_DOCUMENT_BYTES = 8 MiB`，用 `BodyHandlers.ofInputStream()` 拿到流，再 `readNBytes(上限 + 1)`——多读一个字节就能判断是否越界，越界直接抛异常。用 `ofString()` 则没有这个保护，对方返回一个大响应就能把内存吃满。

**第六，重定向绝不自动跟随，凭据只发给同一个 authority。** `HttpClient` 设成 `Redirect.NEVER`，手工跳，最多三次。原因在 blob 下载：仓库会把请求重定向到对象存储，那是另一台主机，如果把 `Authorization` 一起带过去，等于把仓库令牌交给三方。所以跳转前比较 `target.getAuthority()` 与仓库的 authority，只有一致时才带凭据。这是 ACR 加 OSS 回源场景的关键安全点，也是本 demo 里出现两个假服务的原因。

**第七，清单 JSON 用 Jackson 3 解析。** 包名从 `com.fasterxml.jackson` 换成了 `tools.jackson`，入口类是 `tools.jackson.databind.json.JsonMapper`。本模块取的是 `schemaVersion`，生产项目用同一个 `JsonMapper` 读取镜像大小、平台、层数。

**第八，为什么造假服务而不是调真 ACR。** 真仓库要账号密码，把凭据写进 demo 就是提交密码；网络不通时 demo 跑不起来；真实服务的行为你控制不了，而演示 401 与 404 分支需要它按预期返回。

测试替身能替掉客户端，靠的不是「抽一层 HTTP」，而是**端口接口**。生产项目定义了 `ImageRegistry` 端口，`DockerRegistryClient` 是它的一个实现；测试里换成假实现即可。这个类本身没有 HTTP 薄层抽象，它的可测试性来自两件事：实现了一个窄接口，以及有一个 package-private 构造器可以注入本地的 http origin。

**第九，`com.sun.net.httpserver` 是 JDK 内置的极简 HTTP 服务器。** 生产环境不要用它（没有连接池、没有超时控制、性能差），但做测试替身非常合适，因为它零依赖。

**第十，`finally` 里 `stop(0)` 的含义。** `0` 表示立刻关闭，不等现有请求处理完。演示里这样写是为了让 JVM 能马上退出。真实服务关闭时要给宽限期（`stop(5)`），Spring Boot 的 `server.shutdown=graceful` 做的就是同一件事。

## 什么时候该引 SDK

判断依据是「协议里有没有必须自己实现的密码学或状态机」。分界点很清晰：

**该手写**：Docker Registry V2。签名就是「换票 + 带 Bearer」，请求是普通 GET，状态码语义清楚。生产项目一个三百多行（含注释）的客户端就能覆盖标签分页、清单解析、blob 回源，读得懂、改得动。

**该引官方 SDK**：对象存储。生产项目用的是阿里云 OSS 官方 SDK，因为签名算法（`SignVersion.V4`）与错误码体系都不可能自己维护。它的配置方式是 `ClientBuilderConfiguration` 上设三个值：`setSignatureVersion(SignVersion.V4)`、连接超时 5 秒、读取超时 60 秒、`setMaxErrorRetry(2)`。重试交给 SDK，因为只有它知道哪些错误可以重试。

引了 SDK 之后，错误处理要做的是**按错误码分层**，而不是把异常原样往上抛。生产的 `AliyunOssStorage` 把 `OSSException.getErrorCode()` 映射成自己的原因：

- `NoSuchKey` → `NOT_FOUND`；
- `AccessDenied`、`InvalidAccessKeyId`、`SignatureDoesNotMatch`、`SecurityTokenExpired` → `ACCESS_DENIED`；
- 其它 → `UNAVAILABLE`。

这和本模块 `requireAccess` 把 401/404/5xx 分成三个 `Reason` 是同一件事：外部系统的错误词汇表，不该直接泄漏到业务层。

## 完整用法清单

| 写法 | 作用 | 生产项目里的位置 |
| --- | --- | --- |
| `HttpClient.newBuilder().connectTimeout(...)` | 建客户端，设连接超时 | `DockerRegistryClient`，5 秒 |
| `.followRedirects(Redirect.NEVER)` | 关闭自动跳转，手工处理 | 同上，避免凭据外泄 |
| `HttpRequest.newBuilder(URI).timeout(...)` | 建请求，设读取超时 | 同上，30 秒 |
| `.header("Accept", ...)` | 协商返回格式 | 清单拉取，四种类型同列 |
| `.header("Authorization", "Bearer ...")` | 换票之后重放请求 | 401 分支，仅发往仓库自身 |
| `WWW-Authenticate` 解析 | 找 realm / service / scope | 换票入口 |
| `BodyHandlers.ofInputStream()` + `readNBytes` | 带上限地读响应体 | 8 MiB 上限 |
| 状态码分层到 `Reason` | 区分可重试与不可重试 | 401 / 404 / 其余 |
| `HttpServer.create(addr, backlog)` | 起测试替身 | 本模块两个假服务 |
| `sendResponseHeaders(status, len)` | 控制状态码与长度 | `-1` 表示无响应体（重定向） |
| `server.stop(grace)` | 关闭服务 | `server.shutdown=graceful` 同理 |

## 自测三问

**问一**：只设 `connectTimeout`，不设请求超时，什么场景会挂死？

答：服务器接受了 TCP 连接（所以 `connectTimeout` 不触发）但迟迟不返回响应，比如它自己卡在等数据库，或者中间设备悄悄丢包。JDK 的 `HttpClient` 默认没有请求级超时，线程会无限期等待。

**问二**：blob 重定向到对象存储时，为什么不能带 `Authorization`？

答：那个地址是另一台主机，凭据的性质是「访问这个仓库的令牌」，发给三方等于泄漏。所以客户端关闭自动跳转，自己判断目标 authority 是否与仓库一致；跨 authority 的跳转只发请求头里与身份无关的部分。这也是为什么本模块要起第二个假服务，并把收到的头回显出来。

**问三**：404 和 5xx 都失败了，为什么处置方式不同？

答：404 是确定性结果，仓库或标签就是不存在，重试一百次还是 404，只会把失败时间从一秒拖到几分钟；5xx 与网络异常是暂时性的，值得退避后重试。把两者混成一个异常，上层就失去了这个判断依据，只能一刀切。

## 对应生产项目的位置

| 这里的概念 | 生产项目的用法 |
| --- | --- |
| 手写 Registry 客户端 | `infrastructure/registry/DockerRegistryClient` |
| 端口接口 | `application/port/ImageRegistry`（含 `Session` 与 `Reason`） |
| 清单拉取 | `GET /v2/{repo}/manifests/{tag}` |
| `Accept` 协商 | OCI 索引、Docker list、OCI 清单、Docker 清单四种 |
| 认证 | 启动时 `docker login` 供容器运行时拉镜像；客户端自身另有 Basic → Bearer 两步换票 |
| 三层超时 | 5 秒连接、30 秒读取、30 分钟拉取总时长 |
| 响应体上限 | `MAX_DOCUMENT_BYTES = 8 MiB`，`ofInputStream` + `readNBytes` |
| 重定向安全 | `Redirect.NEVER`，手工三次，仅同 authority 带凭据 |
| 假服务测试 | 集成测试连真仓库，单元测试用假实现 |
| 官方 SDK 的例子 | 阿里云 OSS：`SignVersion.V4`、5 秒 / 60 秒 / 重试 2 次、按 `getErrorCode()` 分层 |

## 行数说明

四个类合计约一百九十行（不含注释），`RegistryClient` 占一半以上：换票、重定向、上限读取、状态码分层各自需要一段独立的代码。它们不是可以合并的重复逻辑，而是四种不同的失败模式，删掉任何一段都会留下一个真实的线上问题。

`FakeRegistry` 的两个服务加起来不到五十行，主要成本在于把「仓库」和「对象存储」分成两个监听端口——不分开就无法演示凭据不外泄。
