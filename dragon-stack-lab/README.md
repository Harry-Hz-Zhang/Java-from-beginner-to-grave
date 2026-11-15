# dragon-stack-lab

这个工程是 `dragon-harness` 产品线用到的 Java 技术栈的逐点验证实验室。每个模块只演示一个知识点，注释回答四个问题：这个东西是什么、语法怎么写、为什么这里需要它、生产项目在哪个类里用。

它不属于生产代码，目的只有一个：让你在读懂 `harness_saas` 和 `harness_runner` 之前，先在可控的小环境里把每个机制单独跑一遍。

## 当前进度

二十一个模块全部写完，全部跑通，并且逐个对照过生产代码。全量 `mvn compile` 通过。

| 编号 | 模块 | 主题 | 状态 |
| --- | --- | --- | --- |
| 01 | ioc-di | 依赖注入、`@Bean(name=)` + `@Qualifier` 消歧、生命周期 | 已验证 |
| 02 | config-properties | record 绑定、七种字段类型、自定义转换器、profile | 已验证 |
| 03 | mvc-validation | 路由、参数校验、`WebResponse` 信封、十个异常分支 | 已验证 |
| 04 | transaction | 事务属性、编程式事务、提交后回调 | 已验证 |
| 05 | mybatis-crud | `BaseMapper` 七个方法、条件构造器、`@MapperScan` | 已验证 |
| 06 | mybatis-audit | 六个审计字段、逻辑删除、乐观锁冲突、`FieldStrategy` | 已验证 |
| 07 | mybatis-sql | 注解动态 SQL、批量插入、行锁、手写分页 | 已验证 |
| 08 | flyway-migration | 五个迁移、校验和、三步改 NOT NULL | 已验证 |
| 09 | security-chain | 过滤器链、两个过滤器、401 与 403 的分工 | 已验证 |
| 10 | crypto-hash | Argon2、AES-GCM、AAD 绑定、脱敏与摘要 | 已验证 |
| 11 | scheduled-async | `@Scheduled` 两种写法、自定义调度器、代理陷阱 | 已验证 |
| 12 | virtual-threads | 三种创建方式、Spring 开关、pinning 对照实验 | 已验证 |
| 13 | sse-streaming | `SseEmitter`、心跳、三回调、文件下载 | 已验证 |
| 14 | redis-cache | Lua 原子脚本、hash tag、负缓存、503 降级 | 已验证 |
| 15 | autoconfiguration | 自建 Starter、条件装配、装配顺序 | 已验证 |
| 16 | process-builder | 调外部命令、双线程 drain、超时与清理 | 已验证 |
| 17 | jsonrpc-stdio | 进程间 JSON-RPC 2.0、pending 表、帧上限 | 已验证 |
| 18 | completable-future | 手建 future + 虚拟线程、取消传播、背压 | 已验证 |
| 19 | mapstruct-lombok | 注解处理器协作、两跳映射、编译期报错 | 已验证 |
| 20 | testing-strategy | 全量启动 + MockMvc、切片对比、Testcontainers | 已验证（5 条测试全绿） |
| 21 | external-sdk | 手写 HTTP 客户端、Bearer 换 token、状态码分层 | 已验证 |

## 怎么运行

工程锁定 **JDK 21**：根 `pom.xml` 写着 `<java.version>21</java.version>`，Spring Boot parent 会把它翻译成 `maven.compiler.release=21` 传给编译器，所以编译目标不会跑偏。

本机的 `~/.zshrc` 末尾已经固定了 JDK 21：

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export PATH="$JAVA_HOME/bin:$PATH"
```

所以在你的终端里直接敲 `mvn` 就是 21，不需要额外设置。用一句话确认即可：

```bash
mvn -v | grep "Java version"    # 应该输出 Java version: 21.x
```

如果某次显示的不是 21，通常不是 JDK 装错了，而是当前 shell 没加载 `~/.zshrc`（非交互式脚本、部分 IDE 的 Maven 运行器、CI 都会这样）。这时在命令前显式带上：

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21) && mvn -q compile
```

**关于注解处理这道坎。** JDK 23 起 javac 把 `-proc` 的默认值从 `full` 改成了 `none`，Lombok 和 MapStruct 会被静默跳过——症状不是「编译报错」，而是 `@Data` 该生成的 getter 一个都没有，报错点在离注解很远的地方，极难归因。

`maven.compiler.release=21` 挡不住这件事：release 只约束语言级别和 API，真正干活的是当前 JDK 的 javac。所以根 `pom.xml` 的 compiler 插件显式写了 `<proc>full</proc>`，把注解处理行为与 JDK 版本解耦。已验证：用 JDK 21 和 JDK 26 编译 19 号模块都能通过（后者只多出 Lombok 关于 `sun.misc.Unsafe` 的弃用警告）。

每个模块都是独立的 Maven 模块，可以单独构建和启动。

```bash
# 在 dragon-stack-lab 目录下
mvn -pl 01-ioc-di spring-boot:run

# 整体编译
mvn -q compile
```

下面几个模块需要额外参数或外部依赖，各自的 README 里写清楚了：

| 模块 | 额外要求 |
| --- | --- |
| 02 | 带 profile 跑：`-Dspring-boot.run.profiles=none` |
| 09 | 端口 18090；curl 要加 `--noproxy '*'` |
| 13 | 端口 18091，是常驻 Web 应用 |
| 14 | 需要本地 Redis：`REDIS_PASSWORD=你的密码 mvn -pl 14-redis-cache spring-boot:run` |
| 15 | 跑两次对比开关：`-Dspring-boot.run.arguments=--runner.monitor-enabled=false` |
| 20 | 跑测试：`mvn -pl 20-testing-strategy test` |
| 21 | 端口 18092，假仓库随应用启停 |

**端口提醒**：这些模块刻意不使用 8080，因为你的本机 8080 上跑着 `harness_saas`，请求会打到那个服务上，返回 `SESSION_INVALID` 之类的错误码，让你误以为是自己的配置写错了。

## 目录结构

```
dragon-stack-lab/
├── pom.xml                 聚合父工程，只做版本托管，不含任何业务代码
├── README.md               本文件
├── _docs/
│   ├── conventions.md              编写纪律：行数、注释四段式、命名规范
│   ├── coverage.md                 覆盖度台账 + 逐条审查修正记录
│   ├── guide-A-spring-core.md      A 组：一次创建任务的完整链路
│   ├── guide-B-persistence.md      B 组：数据访问的四种复杂度
│   ├── guide-C-security.md         C 组：谁能做，以及数据是什么形态
│   ├── guide-D-concurrency.md      D 组：这段代码跑在哪个线程上
│   ├── guide-E-runner-pattern.md   E 组：怎么造一个能被别人用的东西
│   └── guide-F-build-test.md       F 组：把错误往前提的三种手法
└── 01-ioc-di/ ... 21-external-sdk/
    ├── README.md           演示什么、怎么跑、关键语法点、完整用法清单、自测三问
    ├── pom.xml             只声明这个知识点真正需要的依赖
    └── src/main/java/com/harry/lab/<主题>/
```

## 推荐学习路线

不要按编号一口气读完。按下面六段走，每段先读组级讲稿，再跑模块，最后做自测题。

### 第一段：Spring 内核（01 到 04）

先读 `guide-A-spring-core.md`，它把这四个模块串成了一次完整的请求处理链路。

验收标准：能说清一个 HTTP 请求从进入到落库，中间经过哪些框架组件，各自在哪个模块里演示。

### 第二段：数据层（05 到 08）

先读 `guide-B-persistence.md`。这一组的重点是判断力而不是记忆：什么时候用 `BaseMapper`、什么时候手写 SQL、哪些字段会被自动填充。

验收标准：给一个需求，你能判断该用哪种写法，并且说清哪些列会被框架自动改写。

### 第三段：安全（09 到 10）

先读 `guide-C-security.md`。这一组回答的是「谁能进这道门」和「进来之后看到的数据以什么形态存在」。

验收标准：能说清 401 和 403 分别由谁产生；能判断某个字段该用哈希还是加密。

### 第四段：并发与流（11 到 14）

先读 `guide-D-concurrency.md`。这一组的核心问题是「这段代码跑在哪个线程上」——四个模块所有的坑都源于这个问题没想清楚。

验收标准：能说清虚拟线程适合什么、不适合什么；能说清 SSE 为什么必须显式设 charset。

### 第五段：Runner 模式（15 到 18）

先读 `guide-E-runner-pattern.md`。这是最不像「普通 Spring 教程」的一段，但恰恰是 `harness_runner` 的工作方式。15 号让你把前面所学的东西反过来看：怎么写出一个被别人依赖的库，而不是一个自己启动的应用。

验收标准：能说清 `@AutoConfiguration` 加 `imports` 的配合方式，能说清为什么库模块不该用 `@Component`。

### 第六段：构建期与验证（19 到 21）

先读 `guide-F-build-test.md`。19 号发生在编译期，20 号发生在验证期，21 号发生在你与不受控的外部系统之间。

验收标准：能回答「这个错误能在哪一层被发现，我有没有把它往前挪一层」。

## 这件事的难度不在语法

二十一个模块跑完，你大概会发现语法部分并不难——每个框架的用法翻文档都能查到。真正难的是判断：

**第一，注解不等于生效。** `@Async` 要有 `@EnableAsync` 和代理调用，`@Version` 要有拦截器，`@AutoConfiguration` 要有 `imports` 清单，`@ConfigurationProperties` 要有 `@EnableConfigurationProperties`，`@Scheduled` 要有 `@EnableScheduling`。每一条都是「写了但没效果」，而且都不报错。

**第二，框架的默认值几乎都为了「小流量下不出错」。** `@Scheduled` 单线程、`fixedRate` 会重叠、`supplyAsync` 用公共池、`ProcessBuilder` 不设超时、Jackson 共用配置——每一条在小流量下都没问题，在有负载时全是事故。

**第三，能查到原因比跑得快重要。** 线程名、退出码、原始输出、独立序列化器、分层状态码、stderr 环形缓冲——这些东西一个都不产出业务价值，但它们决定了线上出问题时你是五分钟定位还是五小时。

这三条在 `_docs/coverage.md` 的「审查修正记录」里全都出现过大原型。

## 版本约定

Spring Boot 4.1.1、Java 21、MyBatis-Plus 3.5.18、MapStruct 1.6.3，与 `harness_saas` 和 `harness_runner` 完全一致。这样你在这里学到的写法，复制到生产仓库能直接编译通过。

特别注意 Spring Boot 4 与网上多数教程的差异：

| 变化 | Boot 3 及以前 | Boot 4 |
| --- | --- | --- |
| Web starter | `spring-boot-starter-web` | `spring-boot-starter-webmvc` |
| Jackson 包名 | `com.fasterxml.jackson` | `tools.jackson` |
| JSON 入口 | `ObjectMapper` | `JsonMapper` |
| `@WebMvcTest` 包名 | `boot.test.autoconfigure.web.servlet` | `boot.webmvc.test.autoconfigure` |
| Web 测试依赖 | 含在 `starter-test` 里 | 需额外引 `starter-webmvc-test` |
| Mock 注解 | `@MockBean` | `@MockitoBean` |

遇到对不上的写法，以这个工程的代码为准。

## 行数说明

最初的纪律是每个模块五十行以内。实际执行下来，01 到 05、08 到 15 基本守住了，06、07、16、17、18 明显超出（最多的一百五十行左右）。

超出的部分有两个来源，都是刻意的：

第一是**注释**。`16-process-builder` 的 `CommandRunner` 一百多行里注释占了大半，因为「为什么生产不用 `redirectErrorStream`」「为什么输出上限是必需的」这些问题不写清楚，代码看起来就是一段平平无奇的样板，陷阱全在被省略的部分。

第二是**对照演示**。`17-jsonrpc-stdio` 要演示错误帧、超时、通知、半帧、stderr 五种情况，每种至少十行，五件事无法合并。

每个模块的 README 末尾都有「行数说明」，交代超出部分为什么不能删。没有为了凑数写的代码。

## 每个模块的 README 里有什么

「演示什么」用一两句话说明这个模块要证明的结论。「怎么运行」给命令和真实输出，你可以拿输出和自己的结果对照。「关键语法点」按重要性列出八到十二条，每条都回答「为什么」而不只是「是什么」。「完整用法清单」是一张表，列出这个知识点在生产项目里的**全部**写法，并标注哪些是「生产未用」——这个标注和正面清单同样重要。「自测三问」是三个能检验你是否真懂的问题。「对应生产项目的位置」把知识接回工作。「行数说明」交代超出行数预算的部分为什么不能删。

## 编写规范

见 `_docs/conventions.md`。核心是三条：每个模块五十行以内（对照演示可放宽）、注释必须回答四个问题、每个模块必须能跑起来。
