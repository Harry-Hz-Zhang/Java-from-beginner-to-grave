# A 组讲解：Spring 内核

这一组四个模块（01 到 04）看起来在讲四件不相干的事：依赖注入、配置绑定、Web 接口、事务。实际上它们在讲同一件事的四个阶段——一个请求从进入容器到写入数据库，中间经过的所有框架机制。

读完这份讲解，你应该能回答一个具体问题：在 `harness_saas` 里点一下「创建任务」，从 HTTP 请求进来到数据库多出一行，中间到底发生了什么。

## 一、四个模块的知识骨架

01 号回答「对象从哪来」。Spring 替你 new 对象、替你传依赖。这是后面一切的基础，因为如果没有容器，02 的配置对象无处安放，03 的控制器无法注入服务，04 的事务代理也无处织入。

02 号回答「配置从哪来」。它把外部配置变成不可变对象，并且在对象创建的那一刻做校验。它的位置很特殊：它必须在 01 的容器启动早期完成，所以才有 `static @Bean` 这种看起来奇怪的写法。

03 号回答「请求怎么进来」。它依赖 01 注入服务，依赖 02 读取超时和大小限制，并且它抛出的异常需要 03 自己的异常处理器统一转换。

04 号回答「数据怎么落」。它是 03 调用的服务层里的一层代理，而代理本身就是 01 的容器能力。

所以顺序不能乱。你可能想先学 03 因为接口看得见，但那样你会一直困惑「Service 是怎么跑到 Controller 里的」。

## 二、串讲：一次创建任务的完整链路

想象前端发来 `POST /api/v1/tasks`，请求体里是任务名和副本数。下面按发生顺序逐段说清。

第一段，容器启动时。`@SpringBootApplication` 触发组件扫描，找到 `TaskController`、`TaskService`、`TaskRepository` 三类组件。容器依次创建它们，创建时发现 `TaskController` 的构造器需要一个 `TaskService`，于是先去创建 `TaskService`；`TaskService` 又需要 `TaskRepository` 和一个配置对象，于是继续往下创建。这个「按需递归创建」的过程就是依赖注入，你不需要写任何 new。

第二段，请求到达时。内嵌 Tomcat 把请求交给 Spring 的 `DispatcherServlet`，它根据路径找到 `TaskController.create` 方法。此时参数解析器发现方法参数上标了 `@RequestBody`，就把 JSON 反序列化成 `CreateTaskRequest` 对象。紧接着它看到 `@Valid`，于是把对象交给校验器，校验不通过就抛 `MethodArgumentNotValidException`，请求根本不会进入你的方法体。

第三段，业务执行时。控制器调用服务，服务上的 `@Transactional` 让容器生成一个代理，代理在方法前开启事务、方法后提交。服务内部调用仓储层，仓储层用 MyBatis-Plus 的 `BaseMapper` 插入一行，`MetaObjectHandler` 在插入前把创建时间、版本号、删除标记填好。

第四段，出错时。假设校验失败，`GlobalExceptionHandler` 捕获异常，返回 400 和一个统一结构的 JSON。假设业务抛异常，事务代理捕获后回滚，异常继续向上，同样被 `GlobalExceptionHandler` 转成响应。

第五段，响应返回时。控制器返回的对象被 Jackson 序列化成 JSON 写进响应体。如果方法返回 `ResponseEntity`，状态码和响应头也一并生效。

这五段里，每一段都对应一个模块。你现在回头再看那四个模块，它们不是四个知识点，是一条流水线上的四道工序。

## 三、完整用法清单

下面这些写法在 demo 里没有全部出现，但你在生产代码里会遇到。

### Bean 的注册方式

| 写法 | 用在什么场景 | 注意事项 |
| --- | --- | --- |
| `@Component` / `@Service` / `@Repository` / `@Controller` | 自己能改源码的类 | 四个注解功能相同，差异只在语义 |
| `@Bean` 方法 | 第三方类、需要复杂构造逻辑的类 | 方法名即 Bean 名 |
| `@Configuration` 内的 `@Bean` | 需要保证多次调用返回同一实例 | 有 CGLIB 代理，比 `@Component` 多一层保证 |
| `@Import` | 引入不在扫描范围内的配置类 | 常用于拆分配置 |
| 自动配置 | 框架自己装配 | 由 `AutoConfiguration.imports` 驱动 |

### 依赖注入的方式

| 写法 | 评价 |
| --- | --- |
| 构造器注入 | 推荐。字段可 final，启动期暴露缺失 |
| setter 注入 | 可选依赖时用，会破坏不可变性 |
| 字段注入 `@Autowired` | 不推荐。无法 final，单元测试要反射 |
| 方法注入 `@Lookup` | 极少用，单例 Bean 需要每次拿新实例时用 |

### 消歧与条件装配

| 注解 | 作用 |
| --- | --- |
| `@Primary` | 同类型多实现时定默认 |
| `@Qualifier("名字")` | 在使用处精确指定 |
| `@ConditionalOnMissingBean` | 容器里没有才注册，做「可替换默认实现」 |
| `@ConditionalOnProperty` | 配置开关控制是否注册 |
| `@ConditionalOnClass` | 类路径上有某个类才注册 |
| `@Profile` | 按环境注册 |

### 生命周期

| 钩子 | 时机 | 典型用途 |
| --- | --- | --- |
| 构造器 | 最早 | 只做赋值，不要访问其他 Bean |
| `@PostConstruct` | 依赖注入完成后 | 校验配置、预热缓存 |
| `InitializingBean.afterPropertiesSet` | 同上，接口方式 | 老代码常见，新代码用注解 |
| `ApplicationRunner` | 容器完全就绪后 | 启动同步任务，生产项目用它同步镜像目录 |
| `@PreDestroy` | 容器关闭前 | 关线程池、停容器 |

### 配置绑定

| 写法 | 场景 |
| --- | --- |
| `@ConfigurationProperties(prefix)` + record | 推荐，不可变 + 构造器绑定 |
| `@ConfigurationProperties` + 类 + setter | 老写法，可变 |
| `@DefaultValue` | yaml 缺这个键时的默认值 |
| `@ConstructorBinding` | 显式声明用构造器绑定 |
| `@ConfigurationPropertiesBinding` + `Converter` | 自定义类型转换 |
| `@EnableConfigurationProperties` | 显式登记配置类 |
| `@ConfigurationPropertiesScan` | 扫包登记 |

### Web 层

| 注解 | 用途 |
| --- | --- |
| `@PathVariable` | 路径里的占位段 |
| `@RequestParam` | 查询串参数 |
| `@RequestBody` | 请求体反序列化 |
| `@RequestHeader` | 请求头 |
| `@Valid` | 标在 `@RequestBody` 参数上，触发请求体校验 |
| `@Validated` | 生产只用在一处：`AuthProperties` 上做**配置**校验（不是分组校验） |
| `@AuthenticationPrincipal` | 取登录主体，生产 40 余处 |
| `@ModelAttribute` | 统一给响应加头（`ProjectKeyController` 的 `Cache-Control: no-store`） |
| `@RestControllerAdvice` | 全局异常与全局数据绑定 |
| `@ExceptionHandler` | 指定异常类型的处理，可传数组一次接多种 |
| `@ResponseStatus` | 指定状态码（生产只用于成功的异步资源，202） |

参数级校验是生产最常用的形态：`@RequestParam(defaultValue = "0") @Min(0) @Max(1000000) int offset` 直接把约束标在参数上，校验失败抛的是 `HandlerMethodValidationException`——**不是** `MethodArgumentNotValidException`，全局异常处理器必须单独接它，漏了会变成 500。

### 事务

| 属性 | 默认值 | 生产实际怎么用 |
| --- | --- | --- |
| `isolation` | 跟随数据库 | 28 处显式写 `READ_COMMITTED` |
| `timeout` | 无 | 17 处写 `timeout = 30` |
| `readOnly` | false | 只有 `ProjectApp` 一处 |
| `rollbackFor` | 只回滚 `RuntimeException` | 生产零处，因为业务异常都继承 `RuntimeException` |
| `propagation` | `REQUIRED` | 生产零处，靠「应用服务调独立仓储 Bean」天然共享事务 |

生产还有一条比属性更重要的约定：**长 IO 关在事务外**。`TaskApp` 用 `TransactionTemplate` 把「停容器」这类慢操作放在事务之前，只把短写操作放进 `execute(status -> ...)`。声明式事务做不到这一点，因为整个方法体都在事务里。

七种传播行为里，日常只需要记住三个。`REQUIRED` 是默认，有事务就加入、没有就新建。`REQUIRES_NEW` 是无论如何新建一个独立事务，外层回滚不影响它。`NESTED` 是嵌套子事务，靠保存点实现，只在部分数据库驱动上可靠。

## 四、五个最容易犯的错

第一个，把校验注解放上就以为生效了，忘了写 `@Valid`。这时不报错，只是校验不发生，属于最隐蔽的失效。

第二个，在构造器里访问其他 Bean。此时依赖还没注入完，你会拿到 null 或者触发循环依赖。

第三个，用 `@Component` 加 `@Bean` 方法实现单例。没有 `@Configuration` 的 CGLIB 代理，两次调用会得到两个实例。

第四个，只在类上标 `@Transactional` 而方法是 private 或者被同类内部调用。代理根本没机会介入，事务静默失效。

第五个，把 `@ConfigurationProperties` 标上就以为注册好了。它只是个标记，必须配合 `@EnableConfigurationProperties` 或扫包。

## 五、自测题

答不上来的题，回到对应模块重读注释。

1. 为什么 `@SpringBootApplication` 的类必须放在根包？放高了会怎样，放低了会怎样？
2. 两个 `@Bean(name = "transferClient")` 与 `@Bean(name = "signingClient")` 返回同一个类型，`OrderService` 的构造器参数不写 `@Qualifier` 会怎样？为什么生产要显式命名这两个 Bean？
3. 一个类有两个构造器，主构造器不写 `@Autowired` 会发生什么？为什么生产里 `TaskRuntimeApp` 那个主构造器必须写？
4. 配置类里的自定义转换器为什么必须用 `static` 修饰 `@Bean` 方法？不加会看到什么现象？
5. `@RestController` 和 `@Controller` 的区别体现在响应处理的那个环节？
6. 参数校验失败时，异常是在进入方法体之前还是之后抛出的？
7. `@Transactional` 默认对哪些异常回滚？为什么这条默认值容易造成线上事故？
8. 同一个类里方法 A 调用方法 B，方法 B 上有 `@Transactional`，事务会生效吗？为什么？
9. `@PostConstruct` 和 `ApplicationRunner` 的区别是什么？`RunnerImageApp` 的镜像同步用的是哪个？
10. `readOnly = true` 只是性能提示，还是会影响行为？在什么数据库上影响明显？

## 六、学完这一组之后

你应该能独立完成一件事：给你一个已有的 Service 类，你能说出它是不是单例、依赖是怎么进来的、事务边界在哪、异常会被谁处理。

下一步进入 B 组。那里会出现一个新的疑问：既然 03 的控制器只负责转发，04 的服务层只负责事务，那数据访问层的复杂度到底在哪。B 组四个模块就是回答这个问题。
