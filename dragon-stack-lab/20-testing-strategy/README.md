# 20 测试策略

## 演示什么

控制器契约测试的两种写法。**第一组是生产项目实际在用的**：`@SpringBootTest` 全量启动 + `@AutoConfigureMockMvc(print = MockMvcPrint.NONE)` + `@MockitoSpyBean` 替换依赖，断言用 `assertThat` 加 Jackson 解析信封。**第二组**保留 `@WebMvcTest` 切片写法作为对照，它更轻，但生产没有用它。

## 怎么运行

```bash
mvn -pl 20-testing-strategy test
```

真实输出（`-q` 之外加上 surefire 汇总）。

```
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.061 s -- in com.harry.lab.testing.TaskControllerWebTests
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.112 s -- in com.harry.lab.testing.TaskControllerSliceTests
[INFO] BUILD SUCCESS
```

切片那组启动约零点一秒，全量那组约一秒。差别不大是因为本模块没有数据库和 Redis；生产项目里带 Testcontainers 的上下文要十几秒，所以一个上下文会承载很多条用例。

## 关键语法点

**第一，生产项目零处 `@WebMvcTest`。** 全部控制器契约测试一律写成：

```java
@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
```

`ProjectIntegrationTests`、`IamIntegrationTests`、`RunnerImageIntegrationTests` 都是这个组合。生产测试共二十七个类：二十一个纯单元测试（JUnit + AssertJ + `ApplicationContextRunner`），六个 `@SpringBootTest`，其中五个再叠 `@Testcontainers`。所谓「控制器测试一律用切片」的说法不成立，也不存在任何 `*ControllerTest` 命名的测试类。

**第二，`print = MockMvcPrint.NONE` 是刻意的。** `@AutoConfigureMockMvc` 默认在断言失败时把请求和响应完整打印出来。几百条用例里失败的永远是少数，默认输出会把真正有用的日志淹掉，生产项目因此显式关掉它。

**第三，断言不用 `jsonPath`，也不用 `status().isOk()`。** 生产的写法是先把响应取出来，再断言状态码，再解析响应体：

```java
var response = mvc.perform(builder).andReturn().getResponse();
assertThat(response.getStatus()).isEqualTo(status);
return json.readTree(response.getContentAsString()).path("data");
```

全仓 `assertThat` 出现一千二百二十七处，`jsonPath` 与 `status().isOk()` 各零处。原因是解析成树之后可以断言任意结构，且返回的 `JsonNode` 还能继续传给后续步骤复用。

**第四，响应外层是统一的信封。** `WebResponse{code, message, data}` 对应生产的 `controller/vo/WebResponse`，成功时 `code` 是 `OK`，失败时是业务码（如 `TASK_NOT_FOUND`）。测试断言的是 `code` 和 `data` 里的值，而不是整段字符串——整段比较在字段顺序变化时会误报。

**第五，`@MockitoSpyBean` 与 `@MockitoBean` 各有用途。** spy 保留真实实现，适合「需要确认某个协作对象确实被调到」，生产用它观察 repository 与缓存的调用次数；mock 完全替换实现，适合「只测当前这层的分支」。Boot 3.4 起 `@MockBean` 已废弃，这两个是替代品。切片测试因为不扫描 `@Component`，必须配 `@MockitoBean` 才能启动。

**第六，Boot 4 的包名变了。** `@WebMvcTest`、`@AutoConfigureMockMvc`、`MockMvcPrint` 都在

```
org.springframework.boot.webmvc.test.autoconfigure
```

旧教程里的 `org.springframework.boot.test.autoconfigure.web.servlet` 已经不存在，照抄会得到 `package ... does not exist`。同时需要额外引 `spring-boot-starter-webmvc-test`，只引 `spring-boot-starter-test` 时能编译但运行会缺东西。

**第七，切片是更轻的替代方案，但不是生产的默认选择。** 切片只装配控制器、消息转换器、参数解析器、校验器，一两秒就绪，适合本地快速验证路由与参数绑定。它测不到过滤器顺序、Tomcat 的连接超时、依赖完整上下文的自动配置。生产项目选择全量启动，是为了让「测试通过」等价于「这个上下文能上线」。

## Testcontainers 怎么写

H2 和真实 MySQL 的行为差异会掩盖 bug：`LIMIT ? OFFSET ?` 的参数绑定、`FOR UPDATE` 的锁范围、JSON 字段类型、大小写敏感性，全都不一样。所以持久层测试要用真库。

2.x 的坐标与 1.x 不同，且容器类不再泛型。以下片段可直接编译（依赖坐标取自 `harness_saas/pom.xml`）：

```xml
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>testcontainers-junit-jupiter</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>testcontainers-mysql</artifactId>
    <scope>test</scope>
</dependency>
```

生产的完整形态是双容器（MySQL + Redis），再把这四个连接信息注入 Spring：

```java
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class ProjectIntegrationTests {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }
}
```

四个要点。第一，`MySQLContainer` 在 2.x 里是**非泛型**类，写 `MySQLContainer<?>` 编译不过，直接 `new MySQLContainer("mysql:8.4")`。第二，`static final` 的容器在整个测试类之间共享，不会每条用例起一次。第三，`@DynamicPropertySource` 必须在容器启动后才能取到端口，所以不能用普通的 `@TestPropertySource`。第四，容器启动要十几秒，这类测试不该放在每次保存都跑的快速反馈环里。

本模块没有把这些依赖真正引入，原因写在 pom 注释里：本机仓库拿不到 testcontainers 构件。

## 完整用法清单

| 写法 | 作用 | 生产项目里的位置 |
| --- | --- | --- |
| `@SpringBootTest` | 全量启动上下文 | 六个类 |
| `@AutoConfigureMockMvc(print = MockMvcPrint.NONE)` | 注入 MockMvc 并关闭默认打印 | 控制器契约测试 |
| `assertThat(response.getStatus())` | 状态码断言 | 全部集成测试 |
| `json.readTree(...).path("data")` | 解析信封后取值 | `call()` 辅助方法 |
| `@MockitoSpyBean` | 保留实现、观察调用 | `ProjectIntegrationTests` 的 `keys`、`cache` |
| `@MockitoBean` | 完全替换依赖 | 需要隔离协作对象的用例 |
| `@WebMvcTest(X.class)` | 只装 Web 层（本模块对照用） | 生产零处 |
| `@Testcontainers` + `@Container` | 真实 MySQL / Redis | 五个类 |
| `@DynamicPropertySource` | 容器起来后注入连接信息 | `spring.datasource.*`、`spring.data.redis.*` |

## 自测三问

**问一**：生产为什么不用 `@WebMvcTest` 测控制器？

答：因为它只装配 Web 层，测出来的上下文与上线时的上下文不是同一个。生产的控制器依赖鉴权过滤器、统一异常处理、消息转换器配置，这些在切片里要么被裁掉、要么要手工重建。全量启动慢一些，但「通过」这件事的含金量更高；启动成本由 `@Testcontainers` 的静态容器和上下文缓存分摊掉。

**问二**：MockMvc 测不到什么？

答：Tomcat 层面的一切。真实的连接超时、优雅停机、过滤器链的执行顺序、请求体大小限制、大文件分块传输。这些必须用 `@SpringBootTest(webEnvironment = RANDOM_PORT)` 加真实 HTTP 客户端。

**问三**：为什么断言用 `assertThat` 加树解析，而不用 `jsonPath`？

答：树解析能拿到一个可复用的 `JsonNode`，后续步骤可以继续取字段、做二次断言；`jsonPath` 只能表达一次匹配。而且状态码、业务码、数据三层的断言可以并列写，失败信息更直接。生产项目一千二百二十七处 `assertThat`、零处 `jsonPath`，是长期演进后的结果。

## 对应生产项目的位置

| 这里的概念 | 生产项目的用法 |
| --- | --- |
| 全量启动 + MockMvc | `ProjectIntegrationTests`、`IamIntegrationTests`、`RunnerImageIntegrationTests` |
| 响应信封 | `controller/vo/WebResponse{code, message, data}` |
| 断言方式 | `assertThat` + `ObjectMapper.readTree`，一千二百二十七处 |
| `@MockitoSpyBean` | `ProjectIntegrationTests` 的 `ProjectKeyRepo`、`ProjectKeyCache` |
| Testcontainers | `harness_saas/pom.xml` 声明 `testcontainers-junit-jupiter`、`testcontainers-mysql` |
| 测试基础包 | `spring-boot-starter-test`，由父 BOM 统一版本 |

## 行数说明

本模块是四个源文件（`Lab20Application`、`TaskController`、`TaskStore`、`WebResponse`）加两个测试类。测试代码比被测代码长，这在契约测试里是正常的：控制器的价值在于契约，而契约要靠测试固定下来。

`TaskController` 只保留三条路径——成功、目标不存在、校验失败，其余逻辑都在 `TaskStore` 里，这样测试只需要替换一个依赖。
