# 07 手写 SQL：注解、动态标签、行锁与批量

## 演示什么

`BaseMapper` 覆盖不到的三类需求：加锁读、可选条件分页、带版本校验的原子更新；以及两类常被忽略的写法：`foreach` 批量插入、`@Param` 包 POJO 的整行更新。最后用一个 `@Transactional` 服务把 `FOR UPDATE` 真正放进事务里。

## 怎么运行

```bash
mvn -pl 07-mybatis-sql spring-boot:run
```

真实输出。

```
第 1 页（每页 2 条）= [任务5, 任务4]
第 2 页（每页 2 条）= [任务3, 任务2]
只看 RUNNING      = [任务4, 任务2]
名字含 3          = [任务3]
换个项目就查不到  = 0 条
锁内改名影响行数 = 1
批量插入 = 2 行，重试同一批 = 0 行
整行更新影响行数 = 1
投影查询 = [TaskNameRow[id=1, taskName=任务1], ...]
软删除影响行数 = 1
软删除后总数   = 6 条
```

第五行证明租户条件不是装饰：换个 `project_uuid` 立刻查不到。第七行证明 `ON DUPLICATE KEY UPDATE id = id` 让重试的批次静默跳过已提交行，而不是报主键冲突。

## 关键语法点

第一，`#{}` 是预编译占位符，天然防注入；`${}` 是字符串拼接，等于把注入漏洞写进代码。

第二，**多参数建议写 `@Param`，但不是必须**。早年教程说「两个以上参数不写 `@Param` 就会报 `BindingException`」，这条在 Spring Boot 项目里是错的：Spring Boot parent 默认开启 `-parameters` 编译参数，MyBatis 能直接按形参名解析。生产有二十四个多参数方法一个 `@Param` 都没写，照样跑得很好，例如 `ProjectMapper.java` 第 25 行、`MembershipMapper.java` 第 43、56、64、70、76、82 行、`UserMapper.java` 第 31 行。正确表述是：**生产两种写法都有；写 `@Param` 更稳，不写也能跑，读代码时不要以为不写是错的**。

第三，`<script>` 把注解里的字符串当成 XML 片段解析，`<if test="status != null">` 只在条件成立时拼这段 SQL，`test` 里是 OGNL 表达式。

第四，模糊查询一律写成 `LIKE #{pattern} ESCAPE '!'`，pattern 由 `SqlSearch.contains` 生成。转义字符选 `!`，先把 `!` 自身替换成 `!!`，再转义 `%` 与 `_`。直接在 SQL 里写 `CONCAT('%', #{x}, '%')` 的写法在生产一处都没有。

第五，`FOR UPDATE` 是行级悲观锁，锁在**事务提交时释放**。`CommandLineRunner` 上没有事务，每条语句自动提交，直接调用等于没锁。所以本模块单独抽了 `Lab07Service`，把 `lockByUuid` 与 `updateById` 放在同一个 `@Transactional` 方法里。

第六，`@Transactional` 靠代理生效，同类内部自调用不经过代理。服务方法必须从外部 Bean 调用，这也是它被单独拆成一个类的原因。

第七，`softDelete` 一条语句改**四列**：`deleted=1`、`version=version+1`、`last_key_uuid=#{keyUuid}`、`updated_at=UTC_TIMESTAMP(3)`。本模块早先的版本只改三列，漏掉了操作密钥这一列，与生产 `TaskMapper.java` 第 31 到 33 行不一致。

第八，批量插入用 `@Insert` 加 `<foreach>`，整仓只有这一处。显式写 `jdbcType` 是为了 null 参数也能绑定——不写时驱动有时无法推断类型，尤其是 null 的 `VARCHAR`。`ON DUPLICATE KEY UPDATE id = id` 让重试的批次跳过已提交的行。

第九，`@Param("row")` 可以一次带走整个 POJO，SQL 里用 `#{row.taskName}` 取值，适合字段多、又不适合用 `updateById` 的整行更新，见生产 `RunnerImageMapper.java` 第 31 到 38 行。

第十，投影查询直接用 `record` 当返回类型。列名靠 `map-underscore-to-camel-case` 映射成构造参数名，`task_name` 自动对上 `taskName`。生产更进一步，用 `AS organization_active` 把 SQL 表达式映射成 Boolean，见 `ProjectMemberMapper.java` 第 17 到 30 行。

第十一，分页的 `limit` 上下界不指望调用方自觉。生产写在 Controller 的参数校验上：`@Min(0) @Max(1000000) int offset`、`@Min(1) @Max(200) int limit`，越界请求直接返回 400，见 `TaskController.java` 第 34 到 35 行。本模块没有 Web 层，所以这层保护只是约定。

第十二，手写 SQL 还有一个坑：更新成功后对象的 `version` 不会自动加一，因为这条 SQL 不经过乐观锁拦截器。本地要自己 `setVersion(getVersion() + 1)`，否则下一次带版本条件的更新必然影响 0 行。

## 完整用法清单：五十八条注解 SQL 的写法分类

| 项 | 数量 | 说明 |
| --- | --- | --- |
| `@Select` | 34 | 单表查询、多表 JOIN、投影、加锁读 |
| `@Update` | 23 | 软删除、状态流转、整行更新、批量清理 |
| `@Insert` | 1 | 只有 `MessageLogMapper` 的批量写入 |
| `@Delete` | 0 | 生产不做物理删除 |
| XML mapper | 0 | `src` 下没有任何 `*.xml` |
| `<script>` | 6 | 需要动态标签时才包一层 |
| `<if>` | 5 | 可选筛选条件 |
| `<foreach>` | 2 | 一处批量插入，一处 `IN` 列表 |
| `@Param` | 32 个方法 | 另有二十四个多参数方法没写 |

合计 34 + 23 + 1 = 58 条注解 SQL，全部写在 Mapper 接口里。

## 第四类：什么时候必须离开 MyBatis

除了加锁读、动态分页、状态流转，还有第四类需求让生产直接换用 `JdbcClient`：SQL 里出现 `CASE WHEN`、`COALESCE(:x, col)`、多表 `DELETE JOIN` 这类写法时，注解字符串会膨胀到无法维护，而项目又坚持不用 XML。

典型例子是 `RunnerImagePullRepoImpl.java` 第 88 到 137 行：`finish` 用 `COALESCE(:layers, layers_done)` 做「传了就更新、没传保留」，`recover` 用 `CASE WHEN attempts < :maxAttempts` 决定回退还是置失败，`purge` 用多表 `DELETE p FROM ... LEFT JOIN ...` 清理历史标记。这三条都是 `JdbcClient` 的命名参数加 `.update()`，没有走 MyBatis。

判断标准很简单：**能放进一条注解并且还读得懂，就留在 MyBatis；出现分支表达式或多表删除，就换 `JdbcClient`**。

## 对应生产项目的位置

| 这里的概念 | 生产项目的用法 |
| --- | --- |
| `lockByUuid` 加锁读 | `TaskMapper.java` 第 17 行，调用方是 `TaskRepoImpl.java` 第 43 行 |
| 分页带租户条件 | `TaskMapper.java` 第 23 行，每个分页查询都带 `org_uuid` 与 `project_uuid` |
| `softDelete` 四列更新 | `TaskMapper.java` 第 31 到 33 行 |
| `foreach` 批量插入 | `MessageLogMapper.java` 第 16 到 40 行 |
| `@Param("row")` 整行更新 | `RunnerImageMapper.java` 第 31 到 38 行 |
| 投影与列别名 | `ProjectMemberMapper.java` 第 17 到 30 行，`ProjectMemberRow` 是 `*DO` 的子类 |
| `LIKE ... ESCAPE '!'` | `SqlSearch.java` 第 6 到 9 行 |
| 第四类手写 SQL | `RunnerImagePullRepoImpl.java` 第 88 到 137 行，改用 `JdbcClient` |

## 行数说明

五个类合计一百五十六行（不含注释，含 `package`、`import` 与注解里的 SQL 文本）。超出八十行纪律的部分主要来自 `TaskMapper`：五种写法各占一段注解 SQL，把 SQL 本身算进代码行数，它一个类就是五十行。这也是生产坚持「能注解就注解、太长就换 `JdbcClient`」的现实原因。

## 自测三问

第一，把 `Lab07Service` 上的 `@Transactional` 去掉，程序还能跑通吗，`FOR UPDATE` 还起不起作用？

第二，`SqlSearch.contains` 里三个 `replace` 的顺序如果反过来，输入 `100%` 会得到什么 pattern，查询结果会怎样？

第三，`insertBatch` 为什么要写 `ON DUPLICATE KEY UPDATE id = id`，如果改写成普通的 `INSERT`，重试同一批会发生什么？
