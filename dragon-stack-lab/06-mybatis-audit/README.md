# 06 审计字段：自动填充、乐观锁、逻辑删除

## 演示什么

三件框架替你做的事：插入时自动填审计字段、更新时自动校验并递增版本号、删除时只置标记不删行。同时把 `FieldStrategy` 的取舍讲清楚：哪些字段永远不许改，哪些字段必须能被清空。

## 怎么运行

```bash
mvn -pl 06-mybatis-audit spring-boot:run
```

真实输出（时间戳每次不同）。

```
插入后 version=0 deleted=false createdBy=1 createdAt=2026-11-08T10:59:01.710890
更新后 version=1
清空后 system_prompt=null task_name=镜像同步-已改名
同版本连改两次 = 1 / 0
softDelete 影响行数 = 1
删除后 selectById = null
库里 deleted = 1
```

七行输出对应七件事：自动填充、乐观锁自增、`ALWAYS` 置空、对照组保留旧值、版本冲突、逻辑删除、`@TableLogic` 过滤。

## 关键语法点

第一，审计字段是**六个**，不是五个：`createdBy`、`updatedBy`、`createdAt`、`updatedAt`、`version`、`deleted`，加上主键 `id` 一共七列。漏掉两个 `by` 字段是读生产代码时最常见的偏差。

第二，`@TableLogic` 让框架生成的删除变成 `UPDATE ... SET deleted = 1`，并给所有查询自动追加 `WHERE deleted = 0`。

第三，`@Version` 只是标记，必须配合 `OptimisticLockerInnerInterceptor` 才生效。冲突时影响行数为 0，生产据此抛 `BusinessException.conflict()`。

第四，`@TableField(fill = FieldFill.INSERT)` 声明插入时由 `MetaObjectHandler` 填值，`INSERT_UPDATE` 表示插入和更新都填。

第五，`MetaObjectHandler` 只填四个字段，`createdBy` 与 `updatedBy` 刻意不填，因为拦截器拿不到请求上下文。这两个值由仓储层从当前操作人取出来再 `set`。

第六，`@TableField(updateStrategy = FieldStrategy.NEVER)` 让字段永不参与 UPDATE，这是代码级的防误改保护。

第七，`@TableField` 的 `updateStrategy` 注解默认值是 `FieldStrategy.DEFAULT`，不是 `NOT_NULL`。`DEFAULT` 再按全局 `update-strategy`（默认也是 `NOT_NULL`）解析，最终才得到「跳过 null」的行为。

第八，想显式把字段更新成 null，必须标 `ALWAYS`。`systemPrompt` 是典型例子，注释写着「null keeps the default identity」。

第九，`updateById` 传进来的对象要带 `version`，乐观锁条件才会拼进 WHERE。不带 `version` 的更新等于跳过版本校验，这是排查「锁为什么不生效」的第一个检查点。

第十，逻辑删除与唯一索引的配合有**两种并存的做法**，详见下一节，不要只记住一种。

## 完整用法清单：FieldStrategy 全量表

| 取值 | 行为 | 生产用例 |
| --- | --- | --- |
| `DEFAULT` | 注解默认值，跟随全局 `update-strategy`（默认 `NOT_NULL`） | 绝大多数普通字段，如 `taskName` |
| `NEVER` | 永不进入 UPDATE 的 SET 子句 | 创建后不可变的 `taskUuid`、`orgUuid`、`projectUuid`、`workspaceUri`、`createdKeyUuid` |
| `NOT_NULL` | 值为 null 时跳过该列 | 显式声明时与默认行为一致 |
| `NOT_EMPTY` | 值为 null 或空字符串时跳过 | 生产未使用 |
| `ALWAYS` | 无条件进入 SET 子句，null 也会写成 NULL | 可清空的配置字段：`image`、`mcpServers`、`model*`、`systemPrompt`、`metadata` |

3.5.18 的 `FieldStrategy` 只有这五个取值。早年版本还有一个 `IGNORED`，已经被移除，看到旧文章里写它时要知道那是历史写法。

数量对照：`TaskDO` 是 **7 处 `NEVER` + 10 处 `ALWAYS`**；全仓 `NEVER` 共 78 处、`ALWAYS` 共 19 处。也就是说 `ALWAYS` 是少数派，只留给「允许清空」的字段。

## 完整用法清单：MetaObjectHandler 填什么 / 不填什么

| 字段 | 是否自动填 | 何时填 | 值从哪里来 |
| --- | --- | --- | --- |
| `createdAt` | 是 | INSERT | `LocalDateTime.now(ZoneOffset.UTC)` |
| `updatedAt` | 是 | INSERT 与 UPDATE | 同上，每次更新刷新 |
| `version` | 是 | INSERT | 固定 `0L`，之后由拦截器自增 |
| `deleted` | 是 | INSERT | 固定 `false` |
| `createdBy` | 否 | 由调用方 set | 仓储层从当前操作人取，见 `UserRepoImpl.java` 第 24 到 25 行 |
| `updatedBy` | 否 | 由调用方 set | 同上，每次写操作都由仓储层覆盖 |
| `id` | 否 | 数据库自增 | `IdType.AUTO` 插入后回填 |

`strictInsertFill` 的语义是「只在该字段为 null 时才填」，所以你手动 `set` 过的值不会被覆盖。

## 逻辑删除与唯一索引：两种并存的做法

早年教程说「唯一索引必须带上 deleted 列」，生产的实际做法是两种并存，各有各的适用场景。

第一种是**函数唯一索引**，把 `deleted = 0` 揉进索引表达式，软删行不占用键位：

```sql
UNIQUE KEY uk_svc_runner_image_active_tag ((CASE WHEN deleted = 0
    THEN CONCAT(image_repository, ':', image_tag) END))
```

用在 `V8__create_runner_image.sql` 第 20 到 21 行、`V7` 第 67 到 68 行、`V1` 第 74 行的「单超级管理员」约束。

第二种是**普通唯一键，不含 deleted**，软删行继续占着键位，重新加入时靠 `restore()` 把那一行复活：

```sql
UNIQUE KEY uk_iam_user_org_ids (user_id, org_id)
UNIQUE KEY uk_iam_user_org_uuids (user_uuid, org_uuid)
```

对应 `V1` 第 71 行，复活逻辑在 `MembershipMapper.java` 第 58 到 64 行与 `ProjectMemberMapper.java` 第 53 到 59 行：`UPDATE ... SET deleted=0, version=version+1 WHERE id=? AND version=? AND deleted=1`。

选哪一种取决于业务语义：同一个人在同一组织里只允许一条成员记录，就用第二种加复活；同一镜像标签只允许一个「未删除」的版本，就用第一种。

## 对应生产项目的位置

| 这里的概念 | 生产项目的用法 |
| --- | --- |
| `BaseModel` 六个审计字段 | `infrastructure/database/model/BaseModel.java` 第 14 到 17 行是 `createdBy` 与 `updatedBy` |
| `@TableLogic` | 所有业务表都有 `deleted` 列，删除一律手写 `softDelete` |
| `@Version` | `updateById` 影响行数不等于 1 就抛 `BusinessException.conflict()` |
| `MetaObjectHandler` | `config/PersistenceConfiguration.java`，与本模块 `Lab06Config` 逐行对应 |
| `FieldStrategy` | `TaskDO` 7 处 `NEVER`、10 处 `ALWAYS`；全仓 78 / 19 |

生产没有任何一处 `deleteById` 调用。删除都是「置标记 + 版本加一 + 记录操作密钥 + 刷新时间」的手写 UPDATE，这样删除本身也受乐观锁保护。

## 行数说明

四个类合计一百五十四行（不含注释，含 `package` 与 `import`）。超出行数纪律的部分集中在 `Lab06Application`：它要在一个 `run` 方法里演示五件事，用两个私有辅助方法（`newTask`、`patch`）把重复的赋值压掉了约十行。如果要严格压到八十行，只能砍掉打印语句，那样五个演示就都看不见证据了。

## 自测三问

第一，把 `Lab06Config` 里的 `OptimisticLockerInnerInterceptor` 注册去掉，`同版本连改两次` 那行会打印什么，为什么？

第二，`createdBy` 标了 `NEVER`，如果某次业务真的需要改创建人，应该怎么做，为什么不应该去掉这个注解？

第三，逻辑删除的表现在有两种唯一索引方案，如果业务要求「同一项目下同名任务只能有一个，且删除后可以重新创建同名任务」，应该选哪一种，还要补什么方法？
