# F 组讲解：构建期与验证

这一组三个模块（19 到 21）正好处在前五组的两个极端上。19 发生在**编译期**——代码还没跑，实现类就已经生成好了；20 发生在**验证期**——代码跑起来了，但只跑一小块；21 发生在**边界上**——你的代码开始和不受你控制的外部系统打交道。

三件事的共同点是：**它们都不是「写业务逻辑」，而是「保证业务逻辑正确」的三种手段。**

## 一、先建立一个判断框架

一个正常的开发循环里，错误可以在四个地方被发现，越靠前越便宜。

第一个地方，**编译期**。写错字段名、类型不匹配、方法签名对不上，编译器直接拒绝。这一层拦住的问题成本最低，因为连打包都不会发生。

第二个地方，**启动期**。配置缺失、Bean 找不到、数据库连不上，应用起不来。这一层的问题成本次低，因为部署流程会在上线前发现。

第三个地方，**测试期**。逻辑写错了、边界没处理、状态码不对。这一层要靠你自己写测试才能发现。

第四个地方，**运行期**。前三个都没拦住，用户在用了，问题出现了——这是唯一会造成真实损失的位置。

`MapStruct` 的价值就是把「字段映射错了」这个问题从第四层提到第一层。`@WebMvcTest` 的价值是把「接口契约变了」从第四层提到第三层。三层超时和状态码分层的价值是把「外部服务挂了」从「整个系统卡死」降级成「这个任务失败」。

**这一组学的不是三个工具，是把错误往前提的三种手法。**

## 二、串讲：从一个字段名写错说起

假设你在写任务详情接口。数据库字段叫 `task_uuid`，对外接口字段叫 `uuid`。

**如果用 `BeanUtils.copyProperties`。** 名字不一致，那个字段就是 `null`。编译通过，测试如果不检查这个字段也能通过，上线之后前端拿到 `uuid: null`，点击详情 404。你在第四层发现了问题。

**如果用 MapStruct。** 你写 `@Mapping(source = "taskUuid", target = "uuid")`。如果手滑写成 `taskUid`，`mvn compile` 直接失败并指出行号。你在第一层就发现了。这是同一件事在两个层次上的差别——代码量几乎一样，反馈成本差了一个数量级。

再看这个接口本身。它有三个分支：查到、查不到、参数不合法。

**如果只靠手工 curl 测。** 每次改代码都要重启应用、拼请求、看响应。查不到的分支你可能从来没测过，因为它需要构造一个不存在的 id。

**如果用 `@WebMvcTest` 三条断言。** `mvn test` 一秒出结果，三个分支全覆盖。而且这三条测试从此成了契约——以后谁改坏了，构建就会红。

最后看这个接口依赖的外部服务。它调的是镜像仓库。

**如果不管超时。** 仓库那边网络抖动，你的线程挂在那里，Tomcat 的线程池被逐渐占满，最后整个服务不可用。一个外部依赖的抖动，变成了你自己的全站故障。

**如果设了三层超时并分层处理状态码。** 连不上两秒失败，连上不回三秒失败，404 立即失败不重试，5xx 才重试。故障被限制在这一个任务上。

三段串下来，规律很清楚：**每一个「本可以在前面发现」的问题，都会在后面以更大的代价出现。**

## 三、完整用法清单

### Lombok

| 注解 | 生成什么 | 注意 |
| --- | --- | --- |
| `@Data` | getter/setter/equals/hashCode/toString | 实体用，需要无参构造 |
| `@Getter` / `@Setter` | 只生成访问器 | 细粒度控制 |
| `@EqualsAndHashCode(callSuper = true)` | 包含父类字段 | 继承时必须写 |
| `@Builder` | 链式构造 | 不可变对象友好 |
| `@Slf4j` | `log` 字段 | 替代手写 Logger |
| `@RequiredArgsConstructor` | 按 final 字段生成构造器 | 构造器注入的简化 |

### MapStruct

| 写法 | 作用 |
| --- | --- |
| `@Mapper(componentModel = "spring")` | 生成带 `@Component` 的实现 |
| `@Mapping(source, target)` | 字段名不一致时对接 |
| `@Mapping(target, ignore = true)` | 排除字段 |
| `@Mapping(target, defaultValue = "x")` | 源为 null 时的默认值 |
| `@Mapping(target, expression = "java(...)")` | 自定义表达式 |
| `@Mapping(target, dateFormat = "...")` | 日期格式化 |
| `@Mapper(uses = OtherMapper.class)` | 组合其它映射器 |
| `@MappingTarget` | 更新已有对象而非新建 |
| `@InheritConfiguration` | 复用父接口的映射配置 |

### 注解处理器的三条顺序

| 顺序 | 处理器 | 为什么在这个位置 |
| --- | --- | --- |
| 1 | `lombok` | 先把 `@Data` 展开成真正的访问器 |
| 2 | `lombok-mapstruct-binding:0.2.0` | 桥接，让 MapStruct 看到 Lombok 生成的成员 |
| 3 | `mapstruct-processor` | 最后执行，此时访问器已存在 |

顺序写错的表现：**编译成功，映射结果全 `null`**。不报错，不警告。

### 测试的分层

| 注解 | 启动范围 | 耗时 | 适用 |
| --- | --- | --- | --- |
| 纯单元测试 + Mockito | 无容器 | 毫秒 | 服务层业务逻辑 |
| `@WebMvcTest` | Web 层 | 1 秒 | 控制器契约 |
| `@DataJpaTest` / `@MybatisTest` | 持久层 | 数秒 | SQL 正确性 |
| `@SpringBootTest` | 完整容器 | 数秒到十数秒 | 跨层流程 |
| `@SpringBootTest(RANDOM_PORT)` | 完整容器 + 真实 Tomcat | 同上 | 过滤器、超时、优雅停机 |
| `@Testcontainers` | 真实 MySQL / Redis | 十几秒 | 持久层集成 |

### 断言方式

| 写法 | 用途 |
| --- | --- |
| `status().isOk()` / `isNotFound()` / `isBadRequest()` | 状态码 |
| `jsonPath("$.field").value(x)` | 字段值，不受顺序影响 |
| `jsonPath("$.list[0].id")` | 数组元素 |
| `jsonPath("$.field").exists()` / `.doesNotExist()` | 存在性 |
| `content().contentType(...)` | 响应类型 |
| `header().string(...)` | 响应头 |

### 外部集成的三层超时

| 层 | 管什么 | 设错的表现 |
| --- | --- | --- |
| `connectTimeout` | TCP 握手 | 只设这个，「连上了但不回」时挂死 |
| 请求 `timeout` | 到响应头到达 | 不设则无限等 |
| 业务总超时 | 含重试 | 不设则重试叠加成几分钟 |

### 状态码分层

| 码 | 含义 | 该不该重试 |
| --- | --- | --- |
| 200 | 成功 | — |
| 401 | 未认证 | 不重试，先登录 |
| 403 | 无权限 | 不重试 |
| 404 | 资源不存在 | **不重试** |
| 429 | 限流 | 重试，带退避 |
| 5xx | 对方异常 | 重试 |

## 四、六个必须知道的坑

第一个，**处理器顺序写错导致映射结果全 `null`**。这是本组最阴的一个，因为代码看起来完全正确、编译零警告。遇到「字段莫名是 null」且涉及 Lombok 的类，第一个要查的就是 `annotationProcessorPaths` 的顺序。

第二个，**两个 `@Mapper` 注解同名**。`org.mapstruct.Mapper` 和 `org.apache.ibatis.annotations.Mapper`。IDE 自动导入经常导错，症状是生成类不存在。

第三个，**`@WebMvcTest` 在 Boot 4 换了包名**。新位置是 `org.springframework.boot.webmvc.test.autoconfigure`，同时需要额外引 `spring-boot-starter-webmvc-test`。照 Boot 3 的文章写会编译失败。

第四个，**MockMvc 测不到 Tomcat 层面**。真实连接超时、过滤器链顺序、大文件分块、优雅停机，全都测不到。要测这些必须 `RANDOM_PORT` 加真实客户端。

第五个，**`@MockBean` 已废弃**。Boot 3.4 起改用 `@MockitoBean`。老写法还能编译但是过时警告。

第六个，**只设 `connectTimeout` 不够**。服务器接受了 TCP 连接但迟迟不返回响应时，`connectTimeout` 根本不触发，线程无限等待。这是外部集成最常见的疏忽。

## 五、自测题

1. 为什么 MapStruct 比 `BeanUtils.copyProperties` 更适合生产？给出两个具体理由。
2. `annotationProcessorPaths` 的三条顺序，各解决了什么问题？只写 lombok 和 mapstruct 会怎样？
3. `@Mapping` 里的 `source` 写错，什么时候报错？如果换成 `BeanUtils` 呢？
4. `componentModel = "spring"` 不加会怎样？两种补救方式各是什么？
5. `@WebMvcTest` 和 `@SpringBootTest` 的启动范围差在哪？为什么前者能快十倍？
6. MockMvc 有哪三类东西测不到？各自该用什么方式测？
7. 为什么 `@Testcontainers` 的容器字段要用 `static`？
8. H2 和真实 MySQL 在哪些行为上不一致？举三个例子。
9. 外部集成要设哪三个超时？只设第一个会出什么问题？
10. 404 为什么不该重试，而 5xx 该重试？

## 六、学完这一组之后

你应该建立起一个判断习惯：**这个错误能在哪一层被发现，我有没有把它往前挪一层。**

字段名靠 MapStruct 挪到编译期，接口契约靠切片测试挪到测试期，外部故障靠超时和状态码分层挪成局部失败。

这个习惯比任何具体框架都重要。框架会换，这个判断不会。

## 七、整个 lab 到此结束

二十一个模块，六组讲稿，覆盖了 `harness_saas`、`harness-runner` 两个 Java 仓库里的全部技术栈。

如果只能记住三句话：

第一，**注解不等于生效**。`@Async` 要有 `@EnableAsync` 和代理调用，`@Version` 要有拦截器，`@AutoConfiguration` 要有 imports 清单，`@ConfigurationProperties` 要有 `@EnableConfigurationProperties`。每一条都是「写了但没效果」。

第二，**框架的默认值几乎都为了「小流量下不出错」**。`@Scheduled` 单线程、`fixedRate` 会重叠、`supplyAsync` 用公共池、`ProcessBuilder` 不设超时、Jackson 共用配置——每一条在小流量下都没问题，在有负载时全是事故。

第三，**能查到原因比跑得快重要**。线程名、退出码、原始输出、独立序列化器、分层状态码——这些东西一个都不产出业务价值，但它们决定了线上出问题时你是五分钟定位还是五小时。
