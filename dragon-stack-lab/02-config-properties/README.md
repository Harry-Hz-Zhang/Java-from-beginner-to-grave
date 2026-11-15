# 02 配置绑定

## 演示什么

这个模块演示如何把 `application.yaml` 里的一整棵配置树，绑定成不可变的 Java 对象，并在绑定完成的那一刻做校验。写法与 `harness-runner` 的 `RunnerProperties` 一致，另外补齐了必填项、列表、`DataSize`、`URI`、脱敏打印、profile 与 `config.import`。

## 怎么运行

```bash
# 默认走 prod profile（application.yaml 里的 spring.profiles.default）
mvn -pl 02-config-properties spring-boot:run

# 只读基线，观察 @DefaultValue 与相对路径原样保留
mvn -pl 02-config-properties spring-boot:run -Dspring-boot.run.profiles=none

# 观察环境变量优先级高于 yaml
RUNNER_MONITOR_INTERVAL=3s mvn -pl 02-config-properties spring-boot:run
```

第一次运行的镜像与数据目录来自 `application-prod.yaml`，`allowed-origins` 是数组里的两项。第二次运行镜像回到 `dragon-harness:local`，数据目录打印 `../data/runner`，说明自定义转换器保留相对路径，转换器不会偷偷把它改成绝对路径。

## 关键语法点

第一，`@ConfigurationProperties("runner")` 的 value 是前缀，不是完整键名。`runner.max-frame-bytes` 这样的 kebab-case 键会自动映射到 `maxFrameBytes` 参数。

第二，record 走构造器绑定，天然不可变。如果写成普通类并有 setter，Spring 会改用 JavaBean 绑定，那是另一套语义。

第三，`@DefaultValue` 标在 record 的组件参数上，yaml 缺这个键时才生效。不标 `@DefaultValue` 的必填项缺了会在绑定阶段失败，基本类型 `int` 则会被绑成 0，必须配 `@Min` 之类的约束。

第四，`@ConstructorBinding` 加紧凑构造器是做校验的位置。校验抛异常等于启动失败，避免带错配置运行。

第五，`Duration`、`DataSize`、`URI`、`List<String>` 都能直接绑定，因为 Spring Boot 内置转换器认识 `10s`、`16MB`、URL 与逗号分隔的单值写法。`Path` 需要自定义转换器，见下一条。

第六，`@Validated` 触发字段级约束（`@NotNull`、`@Min`）；跨字段关系写在紧凑构造器里。生产的两种校验并存，`config/TaskProperties.java:44-46` 就是跨字段判断的例子。

第七，`@ConfigurationProperties` 标了不会自动变 Bean，需要 `@EnableConfigurationProperties` 显式登记或 `@ConfigurationPropertiesScan` 扫包。

第八，`${ENV_NAME:default}` 是取环境变量带默认值的写法，yaml 里可以直接用。

## 转换器为什么这么写

第一，转换器不是 `@Component`。它必须由 `@Bean` 提供，否则容器启动早期取不到它。

第二，`@ConfigurationPropertiesBinding` 是一个 qualifier，让配置绑定专用的 `ConversionService` 选中它，而不会污染 MVC 的全局转换。

第三，`static` 保证自动配置类还没实例化时就能拿到这个 `@Bean` 方法。

第四，`convert` 方法只写 `Path.of(source)`。生产转换器就是这样，测试明确断言「保持相对、`isAbsolute` 与输入一致」。原因是 `data-root` 相对的是进程工作目录，把它绝对化会改变配置语义。生产的 `FileSystemPathConverter` 类上也标了一份 `@ConfigurationPropertiesBinding`，但真正让它进容器的是 `HarnessRunnerCoreAutoConfiguration` 里的 `static @Bean` 方法。

## 完整用法清单：类型与绑定能力

| 类型 | 能否直接绑定 | 生产位置 |
| --- | --- | --- |
| `String` | 能 | `OssProperties.bucket`、`applicationName` |
| `int` | 能，缺键为 0，需 `@Min` | `SessionLogProperties.queueCapacity` |
| `long` | 能 | 本模块 `maxRunBytes`（生产用 `long` 表达字节上限） |
| `boolean` | 能，`true`/`false` | `SessionLogProperties.enabled` |
| `Duration` | 能，`10s`/`30m`/`1h` | `TaskProperties.runtimeIdleTtl` |
| `DataSize` | 能，`16MB`/`4MB` | `TaskProperties.maxPromptSize` |
| `Path` | 需自定义转换器 | `RunnerProperties.dataRoot` 配 `FileSystemPathConverter` |
| `URI` | 能 | `OssProperties.endpoint`、`publicEndpoint` |
| `List<String>` | 能，单值自动包成单元素 | `CorsProperties.allowedOrigins` |
| 多级前缀 | 能，整段前缀绑给一个 record | `harness.session-log`、`harness.task` |
| 必填项 | 不写 `@DefaultValue` 即必填 | `RunnerProperties.environmentFile` |
| 脱敏 | 重写 `toString` | `OssProperties.java:45-48`、`ProjectApiProperties.java:19-22` |
| `@Validated` | 字段注解管单值，紧凑构造器管跨字段 | `TaskProperties.java:44-46` |

## 自测三问

第一，为什么不能把 `FileSystemPathConverter` 标成 `@Component`？配置绑定发生在容器启动极早期，组件扫描的时序不稳定；而且 `@ConfigurationPropertiesBinding` 没有注册能力，标在类上不会自动变成 Bean。

第二，把 `allowed-origins` 从单值改成数组会怎样？两种写法都能绑成 `List<String>`，单值会被包成单元素列表，数组按顺序保留。差别只在 yaml 的可读性与多值场景。

第三，把 `max-run-bytes` 从 `application.yaml` 删掉会怎样？它没有 `@DefaultValue`，属于必填项，绑定阶段直接启动失败，不会带着 0 跑起来。

## 对应生产项目的位置

| 这里的概念 | 生产项目的用法 |
| --- | --- |
| record 型配置绑定 | `harness-runner-core/config/RunnerProperties.java`，十几个 `@DefaultValue` |
| 紧凑构造器校验 | 同一个文件的紧凑构造器里逐个校验 `Duration` 必须为正 |
| 自定义类型转换 | `config/FileSystemPathConverter.java`，只做 `Path.of`，保留相对路径 |
| 显式登记配置类 | `HarnessRunnerCoreAutoConfiguration` 上的 `@EnableConfigurationProperties({...})` |
| profile 分环境 | `harness_saas/src/main/resources/` 下 `application.yaml` 加 local、prod、test、oss 四个 profile 文件 |
| config.import | `harness_saas/src/main/resources/application.yaml:2-3` 挂 `application-oss.yaml` |
| 环境变量占位符 | `${REDIS_HOST:127.0.0.1}`、`${HARNESS_TASK_RUNTIME_IDLE_TTL:10m}` |

## 行数说明

四个类合计七十行业务代码（注释不计），正好在纪律上限。
