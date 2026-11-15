# 覆盖度台账

这张表回答一个问题：`harness_saas` 和 `harness_runner` 里用到的每个 Java 技术点，在这个实验室里有没有对应的 demo。

维护方式：先到生产仓库里逐个 grep 列全，再指派模块，最后用 subagent 逐模块对照审查。不这么做一定会出现「以为写全了，其实漏了 SSE 异步派发」这类情况——本文件最初版本就有六处这样的错误，审查后已全部改正，修正记录见文末。

状态只有三种：**已验证**（跑过并逐项对照过生产代码）、**替代方案**（生产没用这个写法，demo 演示它是为了对比或作为更轻的替代）、**生产未用**（列出来是为了让读者知道哪些是「网上的常见写法但对本项目不适用」）。

## A 组 Spring 内核

| 技术点 | 模块 | 生产项目对应位置 | 状态 |
| --- | --- | --- | --- |
| 构造器注入 + `final` 字段 | 01 | 生产几乎所有类 | 已验证 |
| `@Bean` 声明第三方对象 | 01 | `PersistenceConfiguration`、`OssConfiguration` | 已验证 |
| `@Configuration(proxyBeanMethods = false)` | 01 | `TaskConfiguration`、`OssConfiguration`、`RunnerImageConfiguration` 三处全是关闭 | 已验证 |
| `@Bean(name=)` + `@Qualifier` 消歧 | 01 | `OssConfiguration` 的 `ossTransferClient`/`ossSigningClient` 对 `AliyunOssStorage` | 已验证 |
| 多构造器时的 `@Autowired` | 01 | `TaskRuntimeApp`、`TaskEventHub`、`TaskRunExecutor`、`DockerRegistryClient` 四处 | 已验证 |
| `@Bean(destroyMethod=)` 释放资源 | 01 | `OssConfiguration` 的 `destroyMethod = "shutdown"` | 已验证 |
| `@PostConstruct` 启动即校验 | 01 | `RunnerOwnership`（读开关、建目录、抢文件锁） | 已验证 |
| `@PreDestroy` 停容器与关线程池 | 01 | 七处，`ContainerApp`、`SdkApp`、`RuntimeEvents`、`ContainerMonitor` 等 | 已验证 |
| `@Bean ApplicationRunner` 启动钩子 | 01 | `SecurityConfiguration` 的 `verifyBootstrap` | 已验证 |
| `@Primary` | 01 | 只出现在 `src/test/`，用来替换真实 Bean | 替代方案 |
| 字段注入 | 01 | `TaskAdapter`、`RuntimeRecordAdapter`（MapStruct 抽象类的限制） | 已验证 |
| record 型配置绑定 | 02 | 十个 `@ConfigurationProperties` record | 已验证 |
| `@DefaultValue` | 02 | `RunnerProperties` 十七处 | 已验证 |
| 紧凑构造器跨字段校验 | 02 | `TaskProperties` 的 `eventRetainedBytes >= eventReplayBytes` | 已验证 |
| `@Validated` + jakarta 约束 | 02 | `AuthProperties` | 已验证 |
| `DataSize` / `URI` / `List` 类型绑定 | 02 | `TaskProperties`、`OssProperties`、`CorsProperties` | 已验证 |
| `toString()` 脱敏 | 02 | `OssProperties`、`ProjectApiProperties`、`RunnerImageProperties` | 已验证 |
| 自定义 `Converter<String, Path>` | 02 | `FileSystemPathConverter`（**保留相对路径**，不做 `toAbsolutePath`） | 已验证 |
| `spring.profiles.default` + `spring.config.import` | 02 | `application.yaml` 开头 | 已验证 |
| REST 路由与版本前缀 | 03 | 15 个控制器 | 已验证 |
| `produces` / `consumes` | 03 | 每个控制器类与写方法 | 已验证 |
| `WebResponse<T>` 统一信封 | 03 | `controller/vo/WebResponse` | 已验证 |
| `ErrorCode` 枚举 + `BusinessException` | 03 | `domain/iam/ErrorCode`、`BusinessException` | 已验证 |
| `@Valid @RequestBody` record | 03 | `controller/request/` | 已验证 |
| 参数级校验 `@Min`/`@Max` 标在 `@RequestParam` | 03 | `TaskController` 的 offset/limit | 已验证 |
| `HandlerMethodValidationException` 分支 | 03 | `GlobalExceptionHandler` | 已验证 |
| `@ExceptionHandler` 多类型数组 + 兜底 | 03 | `GlobalExceptionHandler` 十个分支 | 已验证 |
| `@ResponseStatus(ACCEPTED)` 异步资源 | 03 | `TaskRunController` | 已验证 |
| `@ModelAttribute` 统一设响应头 | 03 | `ProjectKeyController` 的 `Cache-Control: no-store` | 已验证 |
| `@RequestBody Map` 做 PATCH 局部更新 | 03 | `TaskController` | 已验证 |
| `@AuthenticationPrincipal` | 03 / 09 | 40 余处 | 已验证 |
| `@RequestHeader` / `@RequestPart` / `MultipartFile` / `@CrossOrigin` | — | 生产零处 | 生产未用 |
| `@Transactional` + `isolation` | 04 | 28 处带 `READ_COMMITTED` | 已验证 |
| `@Transactional(timeout = 30)` | 04 | 17 处 | 已验证 |
| `@Transactional(readOnly = true)` | 04 | 仅 `ProjectApp` 一处 | 已验证 |
| `rollbackFor` | 04 | 生产零处，靠 `BusinessException extends RuntimeException` | 生产未用 |
| `TransactionTemplate` 编程式事务 | 04 | `TaskApp`、`RunnerImageApp` | 已验证 |
| `status.setRollbackOnly()` | 04 | 集成测试里用到 | 已验证 |
| `TransactionSynchronization` 提交后回调 | 04 | `TaskRunApp` 的 `afterCompletion(STATUS_COMMITTED)` | 已验证 |
| 自调用导致注解失效 | 04 | 生产未出现（用独立 Bean 天然避开） | 替代方案 |

## B 组 数据访问

| 技术点 | 模块 | 生产项目对应位置 | 状态 |
| --- | --- | --- | --- |
| `BaseMapper` 免 SQL | 05 | 14 个 Mapper；实际只用到 7 个方法 | 已验证 |
| `@MapperScan`（不用 `@Mapper`） | 05 | `PersistenceConfiguration`；14 个 Mapper 零 `@Mapper` | 已验证 |
| `LambdaQueryWrapper` / `lambdaUpdate` | 05 | 11 个 `*RepoImpl` 共 24 处 | 已验证 |
| `selectCount` / `selectByIds` / `.in` / `.isNotNull` / `.orderByAsc` | 05 | `TaskRunRepoImpl`、`UserRepoImpl`、`ProjectMemberRepoImpl`、`RuntimeRepoImpl` | 已验证 |
| `selectPage` | 05 | **不可用**：项目未注册分页插件 | 生产未用 |
| `selectMaps` / `selectObjs` / `deleteBatchIds` / `deleteById` | 05 | 生产零处 | 生产未用 |
| XML mapper / `IService` / 多数据源 | — | 生产零处，单数据源 | 生产未用 |
| `@TableLogic` 逻辑删除 | 06 | `BaseModel.deleted` | 已验证 |
| `@Version` + `OptimisticLockerInnerInterceptor` | 06 | `BaseModel.version` + `PersistenceConfiguration` 唯一注册的拦截器 | 已验证 |
| `MetaObjectHandler` 自动填充四字段 | 06 | `PersistenceConfiguration.auditFill` | 已验证 |
| `createdBy`/`updatedBy` 由仓储层手工设 | 06 | `UserRepoImpl`、`ProjectMemberRepoImpl` | 已验证 |
| `FieldStrategy.NEVER` | 06 | `TaskDO` 7 处，全仓 78 处 | 已验证 |
| `@TableField(updateStrategy = ALWAYS)` | 06 | `TaskDO` 10 处（`image`/`mcpServers`/`model*`/`systemPrompt`/`metadata`），全仓 19 处 | 已验证 |
| 逻辑删除 + 唯一索引（两种并存） | 06 | ①函数唯一索引 `CASE WHEN deleted = 0`（`V7`/`V8`/`V1`）②普通唯一键不含 deleted + `restore()` 复活（`V1`/`V5`） | 已验证 |
| `@TableField(typeHandler=...)` | — | 生产零处，JSON 列直接映射 `String` | 生产未用 |
| 注解 `@Select` / `@Update` | 07 | 58 条注解 SQL：34 `@Select` + 23 `@Update` + 1 `@Insert` | 已验证 |
| `@Insert` + `<foreach>` 批量插入 | 07 | `MessageLogMapper`（唯一一条 `@Insert`、唯一一处插入用 `<foreach>`） | 已验证 |
| `<script>` / `<if>` 动态 SQL | 07 | 5 处 / 4 处 | 已验证 |
| `@Param` 包 POJO 整行更新 | 07 | `RunnerImageMapper` | 已验证 |
| 多参数不写 `@Param` 也能跑 | 07 | 13 处（`-parameters` 默认开启，按形参名解析） | 已验证 |
| `FOR UPDATE` 行锁 + 事务边界 | 07 | `TaskMapper.lock`/`lockByUuid`，在 `TransactionTemplate` 内调用 | 已验证 |
| 手写 `LIMIT OFFSET` 分页（带租户条件） | 07 | 各 Mapper 的 `page` 方法 | 已验证 |
| `LIKE #{pattern} ESCAPE '!'` | 07 | `UserMapper` + `SqlSearch` | 已验证 |
| 投影 DTO 作返回类型 | 07 | `UserOptionRow`、`ProjectMemberRow`、`OrganizationAccessRow` | 已验证 |
| `JdbcClient` 旁路 MyBatis | 07 | `RunnerImagePullRepoImpl`（`CASE WHEN`、`COALESCE`、多表 `DELETE JOIN`） | 已验证 |
| XML mapper / `@Delete` | — | 生产零处 | 生产未用 |
| Flyway 版本化迁移 | 08 | V1 到 V12 | 已验证 |
| 迁移校验和 | 08 | `validate-on-migrate: true`（需文件库才能演示） | 已验证 |
| 索引 / 唯一键 / CHECK / JSON 列 / utf8mb4 | 08 | `V7` 的建表骨架 | 已验证 |
| 先可空 → 回填 → 改 NOT NULL | 08 | `V11` 的三步法 | 已验证 |
| 纯 DML 种子迁移 | 08 | `V2`、`V3` | 已验证 |
| `baseline-on-migrate` | — | 生产未启用 | 生产未用 |

## C 组 安全

| 技术点 | 模块 | 生产项目对应位置 | 状态 |
| --- | --- | --- | --- |
| `SecurityFilterChain` 无状态配置 | 09 | `SecurityConfiguration` | 已验证 |
| `requestCache.disable()` | 09 | 同上 | 已验证 |
| `requestMatchers(HttpMethod.POST, ...)` | 09 | 登录注册两条 | 已验证 |
| 精确路径与通配并列 | 09 | `/api/service/v1` 与 `/api/service/v1/**` | 已验证 |
| 两个自定义过滤器的相对顺序 | 09 | `addFilterBefore(projectFilter, BearerAuthenticationFilter.class)` | 已验证 |
| `shouldNotFilter` 分流 | 09 | 两个过滤器各跳过一段 | 已验证 |
| `FilterRegistrationBean.setEnabled(false)` | 09 | 防容器重复注册 | 已验证 |
| 401 / 403 分别处理 + `WWW-Authenticate` | 09 | `HttpErrorWriter` | 已验证 |
| 放行 ERROR 与 ASYNC 派发 | 09 | 生产配置第一条规则 | 已验证 |
| CORS 从配置读 + 限定 methods/headers | 09 | `corsConfigurationSource` + `CorsProperties` | 已验证 |
| 排除 `UserDetailsServiceAutoConfiguration` | 09 | 启动类 | 已验证 |
| `@AuthenticationPrincipal` + 自定义 principal | 09 | `SecurityProjectContext` | 已验证 |
| `AuthenticationManager` / `UserDetailsService` / `PasswordEncoder` Bean | — | 生产零处，登录是普通 Controller | 生产未用 |
| Argon2 密码哈希 | 10 | `ArgonPasswordAdapter`（五参数一致） | 已验证 |
| hash + salt 双列存储 | 10 | `iam_user` 两列 + 一致性校验 | 已验证 |
| 时间差防御（假校验） | 10 | `consumeDummyCheck` + `AuthApp` | 已验证 |
| AES-256-GCM | 10 | `TaskSecretCipher` | 已验证 |
| AAD 绑定归属 | 10 | `svc-mcp:v1:` / `svc-model-key:v1:` + uuid；run 行用 `runUuid` | 已验证 |
| 密文格式校验（三段/前缀/nonce 长度） | 10 | `TaskSecretCipher.open()` | 已验证 |
| 构造期 32 字节密钥校验 | 10 | 同上 | 已验证 |
| `mask` 脱敏 + SHA-256 `digest` | 10 | `ProjectKeyCryptoAdapter`、`RedisSessionStore` | 已验证 |
| 密钥轮换 `v2` | — | 前缀留了位置，当前只认 `v1` | 生产未用 |

## D 组 并发与流

| 技术点 | 模块 | 生产项目对应位置 | 状态 |
| --- | --- | --- | --- |
| `@Scheduled(fixedDelay)` | 11 | 10 处中的 7 处 | 已验证 |
| `@Scheduled(fixedDelayString)` / `initialDelayString` | 11 | `RunnerImageApp`、`DockerRegistryLogin`、`ContainerMonitor` | 已验证 |
| `@Scheduled(fixedRate)` / `cron` | 11 | 生产零处 | 生产未用 |
| `ThreadPoolTaskScheduler` 自定义 | 11 | `TaskConfiguration`（poolSize 8 + 线程名前缀） | 已验证 |
| 逐条 try/catch + 每轮限量批量 | 11 | `TaskApp`、`TaskRuntimeApp` | 已验证 |
| `@Async` / `@EnableAsync` / `ThreadPoolTaskExecutor` | 11 | 生产零处，异步用虚拟线程 | 生产未用 |
| 虚拟线程 `ofVirtual().start()` | 12 | `TaskRunExecutor`、`RunnerImagePullApp` | 已验证 |
| `unstarted()` + 句柄 + `@PreDestroy` interrupt | 12 | `RunnerImagePullApp`、`SessionLogApp` | 已验证 |
| `spring.threads.virtual.enabled` | 12 | `application.yaml` | 已验证 |
| pinning：`synchronized` vs `ReentrantLock` | 12 | 生产用 `ReentrantLock` 规避 | 已验证 |
| 平台线程给定时器（不该虚拟化的场景） | 12 | `JsonLineRpcConnection` 的 `dsh-rpc-timeout-` | 已验证 |
| 自定义 `ThreadLocal` | — | 两仓零处，上下文靠显式传参 | 生产未用 |
| `SseEmitter` + 显式 `charset=UTF-8` | 13 | `TaskEventStreams` | 已验证 |
| 心跳 comment 帧（15s） | 13 | `TaskEventStreams` + `TaskProperties` | 已验证 |
| 三回调 `onCompletion`/`onTimeout`/`onError` | 13 | `TaskEventStreams` + `TaskEventHub` | 已验证 |
| `StreamingResponseBody` + UTF-8 文件名 | 13 | `TaskFileController` | 已验证 |
| `AsyncRequestNotUsableException` 处理 | 13 | `GlobalExceptionHandler` | 已验证 |
| `Last-Event-ID` 断线重放 | — | 生产不用；靠 replay 缓冲 + 终态补偿 | 生产未用 |
| `StringRedisTemplate` + 带 TTL 的 `set` | 14 | `RedisSessionStore` | 已验证 |
| Lua 原子脚本（`DefaultRedisScript`） | 14 | 9 个脚本，`RedisRateLimiter` + 两个 Store | 已验证 |
| Cluster hash tag `{iam}` | 14 | `RedisSessionStore`、`RedisProjectKeyCache` | 已验证 |
| 毫秒级 TTL | 14 | 同上 | 已验证 |
| 负缓存 5 秒 + generation 失效 + fence 令牌 | 14 | `RedisProjectKeyCache` | 已验证 |
| `DataAccessException` → 503 降级 | 14 | `GlobalExceptionHandler` | 已验证 |
| 令牌先 SHA-256 再入 Redis | 14 | `RedisSessionStore` | 已验证 |
| `setIfAbsent` 分布式锁 | — | 生产零处 | 生产未用 |

## E 组 Runner 模式

| 技术点 | 模块 | 生产项目对应位置 | 状态 |
| --- | --- | --- | --- |
| `@AutoConfiguration` + `imports` 清单 | 15 | `HarnessRunnerCoreAutoConfiguration` + core/client 两份清单 | 已验证 |
| `@ConditionalOnMissingBean` | 15 | `harnessRunnerRpcConnectionFactory` 等 | 已验证 |
| `@ConditionalOnProperty(matchIfMissing = true)` | 15 | `runner.monitor-enabled` 默认开 | 已验证 |
| `@AutoConfiguration(before = ...)` + `@EnableScheduling` | 15 | `before = TaskSchedulingAutoConfiguration.class` | 已验证 |
| `@DependsOn` 装配顺序 | 15 | `harnessRunnerOwnership` 抢锁先于容器操作 | 已验证 |
| `static @Bean @ConfigurationPropertiesBinding` | 02 / 15 | `harnessRunnerPathConverter` | 已验证 |
| 一个 starter 注册多组配置 | 15 | `runner` / `runner.sdk` / `runner.subscriptions` | 已验证 |
| 库模块零 `@Component` | 15 | core 模块 | 已验证 |
| `ProcessBuilder` 调外部命令 | 16 | `ProcessDockerCommandRunner` | 已验证 |
| 双虚拟线程分别 drain stdout/stderr | 16 | 同上（**生产零 `redirectErrorStream`**） | 已验证 |
| 输出上限 1MB / 16KB + `truncated` | 16 | 同上 | 已验证 |
| `finally` 无条件 `destroyForcibly` + 关流 | 16 | 同上 | 已验证 |
| 关子进程 stdin（等 EOF） | 16 | 同上 | 已验证 |
| 错误码分类（超时/不可用/冲突/不存在） | 16 | `ProcessDockerCommandRunner` + `DockerCliContainerGateway` | 已验证 |
| 长驻 attach 进程的终止顺序 | 16 | `DockerCliContainerGateway` | 已验证 |
| `redirectErrorStream` | — | 生产零处 | 生产未用 |
| JSON-RPC 2.0 over stdio（一行一帧） | 17 | `JsonLineRpcConnection` | 已验证 |
| `error` 帧解析 + code | 17 | 同上 | 已验证 |
| pending 表 + 每请求超时 | 17 | 同上（`dsh-rpc-timeout-` 单线程 timer） | 已验证 |
| `req_` + UUID 作 id | 17 | 同上 | 已验证 |
| 通知与反向请求 `-32601` | 17 | 同上 | 已验证 |
| `maxFrameBytes` + 半帧丢弃 | 17 | 同上 | 已验证 |
| stderr 8KB 环形尾部（只留内存不打印） | 17 | 同上 | 已验证 |
| 关闭时先失败所有 pending | 17 | 同上 | 已验证 |
| 自动重连 | — | 生产没有，断开只能显式 restart | 生产未用 |
| 手建 future + 虚拟线程完成 | 18 | `SdkApp`、`CoreTranslation` | 已验证 |
| `whenCompleteAsync(executor)` 换回调线程 | 18 | `CoreTranslation` | 已验证 |
| `failedFuture` 早失败 | 18 | `JsonLineRpcConnection` | 已验证 |
| 取消传播 + 中断恢复 | 18 | `CoreTranslation`、`ContainerApp` | 已验证 |
| `Semaphore` + 有界队列背压 | 18 | `JsonLineRpcConnection` | 已验证 |
| `supplyAsync` / `thenCombine` / `thenCompose` / `allOf` / `orTimeout` / `handle` | 18 | 生产零命中 | 替代方案 |

## F 组 映射、测试与集成

| 技术点 | 模块 | 生产项目对应位置 | 状态 |
| --- | --- | --- | --- |
| MapStruct `@Mapper(componentModel="spring")` | 19 | 12 个 mapper（7 `*Adapter` + 5 `*Transfer`） | 已验证 |
| `unmappedTargetPolicy = ERROR` | 19 | 12 个里的 7 个 | 已验证 |
| `@Mapping` 字段改名 | 19 | 230 处 `@Mapping`，例如 `taskUuid → uuid` | 已验证 |
| `@Mapping(expression = "java(...)")` | 19 | `TaskAdapter`（解密）、`RunnerImageAdapter`、`SessionLogAdapter` | 已验证 |
| `abstract class` mapper + `@Autowired` 辅助 | 19 | `TaskAdapter`、`RuntimeRecordAdapter` | 已验证 |
| `@Mapping(target, ignore = true)` 忽略审计字段 | 19 | `TaskAdapter` | 已验证 |
| 两跳映射 DO → BO → VO | 19 | `TaskAdapter` + `TaskTransfer` | 已验证 |
| 注解处理器顺序 | 19 | `harness_saas/pom.xml`：`mapstruct-processor → lombok → lombok-mapstruct-binding` | 已验证 |
| `@Mapper(uses=)` / `@MappingTarget` / `@InheritConfiguration` / `defaultValue` | — | 生产零处 | 生产未用 |
| `@Builder` / `@Slf4j` | — | 生产零处（用 `@RequiredArgsConstructor` 66 处 + `LoggerFactory` 15 处） | 生产未用 |
| `@SpringBootTest` + `@AutoConfigureMockMvc` | 20 | 6 个集成测试类 | 已验证 |
| `@MockitoBean` / `@MockitoSpyBean` | 20 | `RuntimeLogRepositoryIntegrationTests`、`ProjectIntegrationTests` | 已验证 |
| AssertJ `assertThat` + Jackson 解信封 | 20 | 1227 处 | 已验证 |
| `@WebMvcTest` 切片 | 20 | 生产零处 | 替代方案 |
| `jsonPath` / `status().isOk()` | 20 | 生产不用 | 替代方案 |
| `@Testcontainers` + 双容器 + `@DynamicPropertySource` | 20 | `ProjectIntegrationTests`（MySQL + Redis） | 已验证（文档） |
| 手写 `HttpClient` 对接 Registry V2 | 21 | `DockerRegistryClient` | 已验证 |
| 三层超时（5s / 30s / 30m） | 21 | `RunnerImageProperties` + `@DefaultValue` | 已验证 |
| 401 → `WWW-Authenticate` → token → Bearer 重放 | 21 | `DockerRegistryClient` | 已验证 |
| 状态码分层到 `Reason` 枚举 | 21 | 同上 | 已验证 |
| 响应体 8MiB 上限 + 流式读取 | 21 | 同上 | 已验证 |
| `Redirect.NEVER` + 仅同 authority 带凭据 | 21 | 同上 | 已验证 |
| Jackson 3 `JsonMapper` + `readTree` | 21 | 同上 | 已验证 |
| OSS 官方 SDK（V4 签名 + 重试） | 21 | `OssConfiguration` + `AliyunOssStorage` | 已验证 |

## 审查修正记录

六个 subagent 分 A–F 组逐模块对照生产代码审查后，改正了下列**把生产事实写错**的地方。这类错误的危害比「漏讲」大得多，因为读者会照着错误的对照去生产代码里找，找不到就怀疑自己。

| 模块 | 原来写的 | 实际情况 |
| --- | --- | --- |
| 01 | 生产用 `@Primary` 消歧 | 主代码零处，只在测试里用；消歧靠 `@Bean(name=)` + `@Qualifier` |
| 01 | `RunnerImageApp` 用 `@PostConstruct` | 它用 `@Scheduled(fixedDelayString=...)` |
| 01 | `ContainerApp` 依赖构造器注入 | 它由 `@Bean` 工厂方法创建 |
| 02 | 转换器把相对路径转绝对路径是对的 | 生产就是 `Path.of`，测试断言「保持相对」 |
| 02 | `@ConfigurationPropertiesBinding` 标类上会被当 `@Component` | 该注解没有注册能力；生产类上也标了一份，同时用 `static @Bean` 注册 |
| 04 | `TaskRunExecutor`、`TaskRuntimeApp` 共八处事务 | 两者零处；真实是 8 个类 28 处 |
| 04 | `readOnly` 在 `service` 包 | 只在 `ProjectApp` 一处 |
| 04 | 创建任务同时写两表且必须同事务 | `create` 没有 `@Transactional`，靠补偿 |
| 05 | `Wrappers` 用在 `*Adapter` | 在 `*RepoImpl`；`*Adapter` 是 MapStruct 接口 |
| 05 | `TaskApp` 改配置只传变化字段 | 传整行 DO，靠 `FieldStrategy` 控制写哪些列 |
| 06 | 审计字段五个 | 六个（加 `createdBy`/`updatedBy`） |
| 06 | `NEVER` 十处、`ALWAYS` 仅 `systemPrompt` | `TaskDO` 是 7 / 10，全仓 78 / 19 |
| 06 | 唯一索引必须带 `deleted` | 两种并存：函数唯一索引，或普通唯一键 + `restore()` |
| 07 | 两参以上必须写 `@Param` | 生产 13 处没写也能跑（`-parameters` 默认开） |
| 07 | `FOR UPDATE` 已演示 | 原 `lockByUuid` 是死代码，从未调用 |
| 08 | 改 V1 重启会报校验和错误 | 内存库每次全新，根本不会比对 |
| 08 | 加非空列必须带默认值 | 生产是「先可空 → 回填 → 改 NOT NULL」三步 |
| 09 | 401/403 返回 `{code,message}` | 是 `{code,message,data}` + `WWW-Authenticate`；且生产不用 `sendError` |
| 10 | 源码里写入了生产真实密钥 | 已换成随机假密钥 |
| 10 | 生产用 `v2` 做密钥轮换 | 只认 `v1`，其余一律拒绝 |
| 11 | 生产用 `@Async` + 自定义 `ThreadPoolTaskExecutor` | 两者零处，异步用虚拟线程 |
| 12 | `SecurityProjectContext` 存 `ThreadLocal` | 它只读 `SecurityContextHolder`；两仓零自定义 `ThreadLocal` |
| 13 | 事件带 `id` 用于断线重连 | 生产不设 `id`，没有 `Last-Event-ID` 重放 |
| 14 | 生产「应该」用 Lua | 生产已经用了 9 个脚本 |
| 14 | 生产用分布式锁 | 没有锁，用 generation + fence 令牌 |
| 15 | `harnessRunnerRpcTransport` / `RpcTransport` | 实际叫 `harnessRunnerRpcConnectionFactory` / `RpcConnectionFactory` |
| 16 | 生产固定开 `redirectErrorStream` | 生产零处，用两个虚拟线程分别 drain |
| 17 | 生产把 stderr 转发到日志 | 生产只留内存，绝不打印（可能含凭证） |
| 18 | 生产二十处 `CompletableFuture` 组合用法 | 全仓零命中，真实形态是手建 future + 虚拟线程 |
| 19 | lombok 必须排最前，生产就是这个顺序 | 生产是 `mapstruct-processor → lombok → binding`；真正必需的是 binding |
| 19 | 字段名不一致会静默变 `null` | 7 个 mapper 用 `ReportingPolicy.ERROR`，是编译失败 |
| 20 | 生产控制器测试一律用 `@WebMvcTest` | 生产零处 `@WebMvcTest`，用 `@SpringBootTest` + `@AutoConfigureMockMvc` |
| 21 | 生产用 `docker login` 后走 `Authorization` 头 | 还有客户端内 Basic → Bearer token 两步 |
