# E 组讲解：runner 的四个模式

这一组四个模块（15 到 18）和前三组有一个根本区别：**它们不是「怎么写业务代码」，而是「怎么造一个能被别人用的东西」**。

`harness-runner` 在产品里是个独立进程，跑在容器里，被 `harness_saas` 调起来。它必须自己是个能加载进别人应用的库（15）、必须能操作外部进程（16）、必须能和那个进程说上话（17）、必须在等待多个外部结果时不浪费线程（18）。这四件事凑成了 runner 的全部对外形态。

## 一、先建立一个判断框架

写业务代码时你关心「逻辑对不对」，写这种基础组件时关心的是另外三件事，优先级从高到低。

第一件，**别人用的时候会不会配错**。所以要有配置对象、要有默认值、要有启动时校验。配错要在启动时就炸，不能等到运行到那一行才炸。

第二件，**别人想换的时候能不能换**。所以要有 `@ConditionalOnMissingBean`、要把接口和实现分开。替换不了意味着测试时只能连真环境。

第三件，**出问题的时候能不能查**。所以要有超时、有退出码、有原始输出、有线程名。没有这些，线上出问题只能靠猜。

三个问题对应四个模块：15 解决前两个，16 和 17 解决第三个，18 解决「等待的时候别浪费」。

## 二、串讲：runner 怎么被启动起来

`harness_saas` 要跑一个任务，它调用 runner 的镜像，容器起来，`main` 方法执行。但这不是一个 Spring Boot Web 应用——它没有端口，只有 stdin 和 stdout。

**第一件事，库怎么被装上。** runner 分成 `harness-runner-core`（库）和 `harness-runner`（应用）。core 里没有任何 `@Component`，所有 Bean 都由 `HarnessRunnerCoreAutoConfiguration` 声明。这个类被 `META-INF/spring/...AutoConfiguration.imports` 登记，Boot 启动时读取清单加载它。想替换容器网关？自己声明一个同类型 Bean，`@ConditionalOnMissingBean` 自动让位。

**第二件事，配置从哪来。** `RunnerProperties` 是 record，带 `@DefaultValue`，紧凑构造器里做校验。`Path` 类型的字段需要自定义 `Converter`，而且这个转换器必须用 `static @Bean @ConfigurationPropertiesBinding` 声明——写成 `@Component` 会静默失效，因为配置绑定服务构建得比组件扫描早。这个坑我在 02 号模块踩过。

**第三件事，怎么操作容器。** 走命令行，不走 SDK。`ProcessBuilder` 开 `redirectErrorStream`，`waitFor` 带超时，超时 `destroyForcibly`，退出码非 0 就抛异常。每类命令的超时时间是配置项，不是硬编码。

**第四件事，怎么和容器内通信。** 容器只暴露 stdin/stdout，所以走 JSON-RPC over stdio，一行一帧。协议用的 `JsonMapper` 必须独立创建，不能跟宿主的 `spring.jackson.*` 配置走。

**第五件事，多个外部结果怎么等。** 启动任务时要同时准备镜像、工作区、密钥。用 `CompletableFuture` 并发，传自己的线程池，`thenCombine` 合并，`exceptionally` 降级，`orTimeout` 兜住慢的那一个。

五件事串起来就是 runner 的一生：**装配 → 读配置 → 调命令 → 说话 → 等结果**。每一件都有一个「看起来更简单的写法」，而那个更简单的写法在真实环境里会出事。

## 三、完整用法清单

### 自动配置的要素

| 要素 | 作用 | 缺了会怎样 |
| --- | --- | --- |
| `@AutoConfiguration` | 声明来自库 | 可以被 `@Configuration` 顶替，但丢失顺序能力 |
| `imports` 清单文件 | 让 Boot 找到它 | 完全不生效，**且不报错** |
| `@EnableConfigurationProperties` | 注册配置对象 | 配置类不是 Bean，注入失败 |
| `@ConditionalOnMissingBean` | 允许宿主替换 | 替换不了，测试只能连真环境 |
| `@ConditionalOnProperty` | 配置驱动的可选组件 | 组件永远存在，误用风险 |
| 库内零 `@Component` | 不依赖宿主扫描路径 | 宿主包名不同就找不到 |
| `@AutoConfiguration(before = X.class)` | 控制装配顺序 | 依赖的 Bean 还没建好 |

### 外部命令的处理

| 处理点 | 写法 | 不做会怎样 |
| --- | --- | --- |
| 参数传递 | `List<String>` | 引号转义、注入问题 |
| 错误输出 | `redirectErrorStream(true)` | 大输出时管道死锁 |
| 等待 | `waitFor(t, unit)` | 永久挂起 |
| 超时清理 | `destroyForcibly()` | 僵尸进程 |
| 成败判断 | `exitValue() != 0` | 把失败当成功 |
| 大输出 | `redirectOutput(File)` | 缓冲区写满死锁 |

### stdio 协议

| 要素 | 约定 |
| --- | --- |
| 帧边界 | 换行符，一行一个 JSON |
| 请求字段 | `jsonrpc` / `id` / `method` / `params` |
| 响应字段 | `jsonrpc` / `id` + `result` 或 `error` |
| 写入 | `writeLine` 之后**必须** `flush` |
| 断开检测 | `readLine()` 返回 `null` |
| 序列化器 | 独立 `JsonMapper`，与宿主配置隔离 |
| 并发请求 | 靠 `id` 匹配，维护 pending 映射 |

### CompletableFuture 的编排

| 写法 | 语义 | 用错的后果 |
| --- | --- | --- |
| `supplyAsync(fn, pool)` | 异步有返回值 | 不传池，污染 `commonPool` |
| `runAsync(fn, pool)` | 异步无返回值 | — |
| `thenCombine(other, fn)` | 两个独立结果合并 | 与 `thenCompose` 混用会拿到嵌套 Future |
| `thenCompose(fn)` | 串行依赖 | 拿不到扁平结果 |
| `thenApply(fn)` | 结果转换 | — |
| `exceptionally(fn)` | 上游异常降级 | 写在中间兜不住后面的异常 |
| `handle((r, ex) -> ...)` | 成败都处理 | — |
| `orTimeout(t, unit)` | 到期结束 | **不取消底层任务** |
| `allOf(...)` | 等全部 | 一个失败不会取消其他 |
| `anyOf(...)` | 任一完成 | — |
| `join()` | 取值，不抛受检异常 | — |

## 四、六个必须知道的坑

第一个，**`imports` 文件名错一个字，整套自动配置消失**。而且不报错、不警告，表现就是「Bean 找不到」。这是 15 号模块存在的全部理由。

第二个，**库里的 `@Component` 是隐性依赖**。它在你的测试环境能扫到，在宿主环境扫不到。判断标准很简单：库的代码里出现 `@Component`、`@Service`、`@ConfigurationPropertiesScan`，就是错的。

第三个，**`ProcessBuilder` 先 `waitFor` 再读输出会死锁**。输出超过管道缓冲区（Linux 通常 64KB）时，子进程阻塞在写操作，父进程阻塞在等退出。本 demo 输出小所以测不出来，生产实现必须用独立线程读或重定向到文件。

第四个，**协议 JSON 不能跟宿主配置走**。宿主配了缩进输出，每帧变多行，`readLine` 只读第一行，协议立即失效。失效方式是偶发解析错误，离原因很远。

第五个，**`orTimeout` 不等于取消**。`Future` 提前结束，线程还在跑。要真停下得自己处理中断。

第六个，**`supplyAsync` 不传线程池会污染 `commonPool`**。`commonPool` 的并行度等于 CPU 核数减一，整个 JVM 共享，框架内部（并行流）也在用。容器里 CPU 限额低时并行度可能只有 1，任务直接同步执行，「并行」完全失效。

## 五、自测题

1. `@AutoConfiguration` 和 `@Configuration` 都能声明 Bean，那前者的价值到底在哪？举两个具体场景。
2. 删掉 `imports` 文件里的类名，应用启动会报什么错？为什么这个错误看起来很像是「Bean 没写」？
3. 为什么库模块里不能出现 `@Component`？在什么情况下它能「碰巧」工作？
4. `@ConditionalOnMissingBean` 的判断依据是「容器里没有」还是「classpath 上没有」？两者差别在哪？
5. `redirectErrorStream(true)` 去掉之后，什么条件下会死锁？为什么小输出的 demo 测不出来？
6. `waitFor` 超时后不调 `destroyForcibly`，子进程会怎样？JVM 退出会带走它吗？
7. stdio 协议为什么用换行符做帧边界，而不用长度前缀？
8. 协议解析为什么不能共用宿主的 `JsonMapper`？给出具体的故障场景。
9. `thenCombine` 和 `thenCompose` 各自返回什么类型？怎么一眼看出用错了？
10. `allOf` 里一个任务失败，其余任务会被取消吗？想实现「失败即全停」该怎么做？

## 六、学完这一组之后

你应该能回答一个元问题：**什么样的代码才叫「能被别人用的组件」**。答案是三件事——配错了能在启动时发现、想换的时候能换掉、出问题时能查到原因。

这三条不是编码技巧，是设计意识。前三组教你写的代码是给别人用的功能，这一组教的是给别人用的地基。

下一组进 F 组，也是最后一组。那里不再是运行时，而是构建时和测试时：代码怎么在编译阶段被生成出来，以及你怎么证明它是对的。
