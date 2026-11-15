# 19 编译期代码生成

## 演示什么

Lombok 把 `@Data` 展开成访问器，MapStruct 在编译期生成映射实现类，两者靠 `lombok-mapstruct-binding` 桥接。同时演示两跳映射：`TaskDO → TaskBO → TaskVO`。

## 怎么运行

```bash
mvn -pl 19-mapstruct-lombok spring-boot:run
```

真实输出。

```
第二跳结果 = TaskVO[uuid=uuid-7, name=镜像同步, status=RUNNING, label=镜像同步 / running, createdAt=2026-11-08T19:27:52.142902]
第一跳实现 = com.harry.lab.mapping.TaskAdapterImpl
第二跳实现 = com.harry.lab.mapping.TaskTransferImpl
反向映射   = TaskDO(id=7, taskUuid=uuid-7, taskName=镜像同步, taskStatus=RUNNING)
```

`TaskAdapter` 是抽象类，`TaskTransfer` 是接口，两者都只有声明没有实现，但 `getClass()` 能打印出 `Impl` 结尾的类名——这个类在你的源码里不存在，是编译时生成的。

跑完看一眼生成的代码：

```
19-mapstruct-lombok/target/generated-sources/annotations/com/harry/lab/mapping/
```

每个 mapper 对应一个 `*Impl.java`，那是理解 MapStruct 全部行为的最好材料。

## 关键语法点

**第一，两者都是「编译期代码生成」，不是运行期反射。** 生成的 `.java` 文件与你的源码一样参与编译，最终变成本地字节码，所以性能与手写代码相同。这也解释了为什么它们配置在 `maven-compiler-plugin` 的 `annotationProcessorPaths` 里，而不是写成普通依赖。

**第二，`@Mapper` 有两个同名注解，别导错。** `org.mapstruct.Mapper` 是代码生成器；`org.apache.ibatis.annotations.Mapper` 是 MyBatis 的。两个都叫 `Mapper`，IDE 自动导入时经常导错，症状是编译后找不到 `*Impl` 实现类。

**第三，处理器顺序可以互换，必需的是 binding。** 生产项目 `harness_saas/pom.xml` 的实际顺序是：

```
mapstruct-processor  →  lombok  →  lombok-mapstruct-binding
```

这个顺序不是硬性要求。javac 会把这一列表里的处理器全部注册，谁先谁后由处理器自己的轮次调度决定，互换位置照样能编译、照样生成正确的实现类。网上流传的「lombok 必须排最前」并不成立，生产项目的实际顺序也不是那样。

真正不能省的是 `lombok-mapstruct-binding` 必须和两者同列。它的作用是让 MapStruct 在 Lombok 生成访问器之后能看见那些访问器；缺了它，MapStruct 会认为源类没有任何属性，报「找不到属性」，或者生成一个字段都没赋值的空实现。排在最前或最后都可以，不存在「必须第一个」。

**处理器能不能跑起来，还取决于 JDK 版本，这一条比顺序更容易踩。** 从 JDK 23 起 javac 把 `-proc` 的默认值改成了 `none`，整条 `annotationProcessorPaths` 会被静默跳过：没有任何报错，只是 `@Data` 的 getter 和 `*Impl` 实现类都不生成，报错点跑到很远的地方。`maven.compiler.release=21` 挡不住它——release 只管语言级别，干活的是当前 JDK 的 javac。

所以根 `pom.xml` 的 compiler 插件显式写了 `<proc>full</proc>`。想自己确认这条是否生效，用两种 JDK 各编译一次：

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21) && mvn -pl 19-mapstruct-lombok clean compile
```

`target/generated-sources/annotations/` 下应该出现 `TaskAdapterImpl.java` 和 `TaskTransferImpl.java`。这两个文件存在，才说明处理器真的跑过了。

**第四，`unmappedTargetPolicy = ReportingPolicy.ERROR` 是本模块最值得带走的一条。** 默认策略是 WARN：目标对象上有字段没被映射时只打印一行警告，编译通过，运行时那个字段是 `null`。改成 ERROR 之后，漏映射直接变成**编译失败**。生产项目的十二个 mapper 里有七个显式写了它。

**第五，字段名不一致时必须写 `@Mapping`，漏写会怎样取决于策略。** 在默认 WARN 策略下，源和目标名字对不上时那一对字段不会被映射，运行结果是 `null`，只有一条容易被忽略的警告。在 `ReportingPolicy.ERROR` 下则是**编译失败**，日志会指出哪个属性没有来源。本模块与生产项目都用后者，所以漏写不会静默变成 `null`，而是在编译期就停下来。

**第六，`@Mapping(expression = "java(...)")` 用于「必须写一行代码」的字段。** 比如本模块的 `label` 是由名称和状态算出来的派生字段：

```java
@Mapping(target = "label", expression = "java(text.label(row.getTaskName(), row.getTaskStatus()))")
```

`text` 是抽象类 mapper 用 `@Autowired` 注入的辅助 Bean。生产项目用它做解密（`cipher.decrypt(row.getMcpServers(), row.getTaskUuid())`）和 JSON 列转换。注意没有任何名为 `@MappingExpression` 的注解，那是臆造出来的写法。

**第七，只有抽象类能注入 Bean。** 接口不能有实例字段，也就没法 `@Autowired`，expression 里只能调静态方法。生产项目十二个 mapper 里有两个是抽象类（`TaskAdapter`、`RuntimeRecordAdapter`），原因正是这个；其余十个不需要外部对象，写成接口更轻。

**第八，`@Data` 遇到继承必须配 `@EqualsAndHashCode(callSuper = true)`。** 不写的话生成的 `equals` 只比较本类字段，父类的审计字段被忽略，两个 `createdBy` 不同的对象会被判成相等。生产项目里有十五处这样的组合。

**第九，两跳映射的分工。** `TaskAdapter` 只碰持久化字段（`taskUuid → uuid`），`TaskTransfer` 只碰对外视图（`id` 不外泄）。数据库列改名只动前者，接口字段改名只动后者。生产项目里前者叫 `*Adapter`（七个，repository 层），后者叫 `*Transfer`（五个，service 层），没有 `*Converter` 这个命名，也没有 `application/converter` 这个包。

**第十，Lombok 在 IDE 里需要装插件。** 不装的话 IDE 标红「找不到 getter」，但 `mvn compile` 是成功的。这是编辑器体验问题，不是代码错误。

## 完整用法清单

| 写法 | 作用 | 生产项目里的位置 |
| --- | --- | --- |
| `@Data` | 生成 getter/setter/equals/hashCode/toString | `BaseModel`、各 `*DO` |
| `@EqualsAndHashCode(callSuper = true)` | 子类的 equals 纳入父类字段 | 十五处继承 `BaseModel` 的实体 |
| `@RequiredArgsConstructor` | 为 final 字段生成构造器，替代手写构造函数 | 六十六处，service 与 adapter 层 |
| `LoggerFactory.getLogger(X.class)` | 声明日志对象 | 十五处；生产零处 `@Slf4j` |
| `@Mapper(componentModel = "spring")` | 生成带 `@Component` 的实现 | 七个 `*Adapter` + 五个 `*Transfer` |
| `@Mapper(unmappedTargetPolicy = ReportingPolicy.ERROR)` | 漏映射变编译失败 | 七个 mapper |
| `abstract class` + `@Autowired` | 让 expression 能调注入对象 | `TaskAdapter`、`RuntimeRecordAdapter` |
| `@Mapping(source, target)` | 字段名不一致时对接 | `taskUuid → uuid`、`runUuid → uuid` |
| `@Mapping(target, ignore = true)` | 排除不该映射的字段 | 反向映射时忽略六个审计字段 |
| `@Mapping(target, expression = "java(...)")` | 需要一行代码才能算出的字段 | 解密、JSON 列转换 |
| `annotationProcessorPaths` 三件套 | mapstruct-processor、lombok、binding 同列 | `harness_saas/pom.xml` |

## 自测三问

**问一**：把 `lombok-mapstruct-binding` 从 `annotationProcessorPaths` 里删掉，会发生什么？

答：MapStruct 看不到 Lombok 生成的访问器，会认为 `TaskDO` 没有任何属性，于是报「找不到属性」或者生成一个字段都没赋值的空实现。这是唯一一个删掉会出问题的条目，另外两个的先后顺序互换则没有任何影响。

**问二**：`taskUuid` 改名成 `uuid` 时忘写 `@Mapping`，什么时候能发现？

答：在 `ReportingPolicy.ERROR` 下是编译失败，日志指出 `TaskBO.uuid` 没有来源。如果策略是默认的 WARN，则编译通过、运行时字段为 `null`，只有一行警告——这也是本模块坚持显式写 ERROR 的原因。

**问三**：`componentModel` 不写会怎样？

答：生成的实现类没有 `@Component`，注入时报 `NoSuchBeanDefinitionException`。这时要么补上属性，要么用 `Mappers.getMapper(TaskAdapter.class)` 手动取。生产项目全部用 `spring` 模式。

## 对应生产项目的位置

| 这里的概念 | 生产项目的用法 |
| --- | --- |
| Lombok `@Data` | `infrastructure/database/model/BaseModel` |
| `@EqualsAndHashCode(callSuper = true)` | 十五处继承 `BaseModel` 的实体 |
| MapStruct 适配器 | `infrastructure/repository/*Adapter`，七个 |
| MapStruct 传输映射 | `service/*Transfer`，五个 |
| `ReportingPolicy.ERROR` | `TaskAdapter`、`RunnerImageAdapter`、`SessionLogAdapter` 等七个 |
| 抽象类 mapper | `TaskAdapter`、`RuntimeRecordAdapter` |
| `@Mapping(expression = ...)` | `TaskAdapter` 的解密、`RunnerImageAdapter` 的逗号分隔转 List |
| 处理器清单 | `harness_saas/pom.xml` 的 `annotationProcessorPaths` |
| MapStruct 版本 | 1.6.3，写在 `harness_saas/pom.xml` 的 `<properties>` 里 |

## 行数说明

`BaseModel`、`TaskDO`、`TaskBO`、`TaskVO` 四个是纯数据类，没有逻辑可以压缩——字段本身就是演示内容（名字不一致才能演示 `@Mapping`，父类字段才能演示 `ignore`）。

`TaskAdapter` 与 `TaskTransfer` 各只有两个方法，行数主要花在 `@Mapping` 上。`Lab19Application` 的一半代码在构造测试数据，这是为了能跑通完整的两跳转换。
