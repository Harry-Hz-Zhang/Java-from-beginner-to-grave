# 05 MyBatis-Plus 基础增删改查

## 演示什么

这个模块证明一件事：继承 `BaseMapper` 之后，单表增删改查一行 SQL 都不用写。同时演示 `LambdaQueryWrapper` 为什么比字符串拼条件更安全，以及包扫描为什么比逐个写 `@Mapper` 更贴近生产。

## 怎么运行

```bash
mvn -pl 05-mybatis-crud spring-boot:run
```

真实输出。

```
RUNNING 条数 = 2
两种状态共 3 条，按 id 升序：
  1 / 镜像同步
  2 / 镜像清理
  3 / 日志归档
按 id 批量查回 2 条；空集合直接返回 0 条
重命名影响行数 = 1
```

第一条与第二条状态是 RUNNING，第三条是 IDLE，所以 `countRunning` 只算到 2。列表按 id 升序，说明 `orderByAsc` 生效。

## 关键语法点

第一，`@TableName` 必须显式写。不写时框架按类名转下划线去猜，`TaskDO` 会被猜成 `task_d_o`。

第二，`@TableId(type = IdType.AUTO)` 表示主键交给数据库自增，插入后框架会把自增结果回填到对象上。

第三，驼峰转下划线由 `mybatis-plus.configuration.map-underscore-to-camel-case` 控制，默认开启。字段查出来是 null 时先查这里。

第四，`BaseMapper` 在 3.5.18 里是**二十一个方法名**，不是早年教程说的十七个。新增的四个是 `deleteByIds`、`selectByIds`、`exists`、`insertOrUpdate`；`deleteBatchIds` 与 `selectBatchIds` 已标 `@Deprecated`，替代者就是前两个带 `ByIds` 的方法。实现类由框架在启动时动态生成。

第五，注册 Mapper 有两种写法：`@Mapper` 标具体接口，`@MapperScan` 扫整个包。生产用后者，写在 `config/PersistenceConfiguration.java` 第 16 行；十四个 Mapper 接口上一个 `@Mapper` 都没有。这个模块新增的 `Lab05Config` 就是这一行的复刻。

第六，`Wrappers.lambdaQuery()` 用方法引用代替字符串字段名，字段改名时编译期就能发现。

第七，条件构造器每个方法都有带 boolean 参数的重载，`.eq(flag, ...)` 在 flag 为 false 时不拼这个条件，可以替代 if。

第八，`selectCount` 的签名返回 `Long`，空结果集返回 `0` 而不是 `null`，所以可以直接当 `long` 用。

第九，`selectByIds` 传空集合会拼出非法 SQL，调用方要先判空。生产 `UserRepoImpl.java` 第 76 到 80 行就是 `ids.isEmpty() ? List.of() : mapper.selectByIds(ids)`。

第十，`updateById` 的「只更新非 null 字段」不是 `updateById` 自己的规则，而是字段策略的结果：`@TableField` 的 `updateStrategy` 注解默认值是 `FieldStrategy.DEFAULT`，再由全局 `update-strategy` 解析成 `NOT_NULL`。想显式写 null，字段上要标 `ALWAYS`，见 06 号模块。

第十一，生产并没有「只传变化字段」的用法。`TaskAdapter.taskRow()` 生成的是**整行** DO，靠每个字段自己的 `FieldStrategy` 决定哪些列进 SET 子句。这个模块的 `rename` 只设 id 和名字，是为了让初学者先看清「部分更新」的直觉，读生产代码时不要照搬。

## BaseMapper 完整用法清单

生产十一个 `*RepoImpl` 里真正调用过的方法只有七个，其余十四个方法名一次都没出现。

| 方法 | 生产调用次数 | 说明 |
| --- | --- | --- |
| `selectById` | 19 | 按主键查单条 |
| `selectOne` | 14 | 条件查单条，配合 `lambdaQuery` |
| `insert` | 12 | 单条插入，主键回填 |
| `updateById` | 12 | 整行更新，列由 `FieldStrategy` 决定 |
| `selectList` | 5 | 条件查列表 |
| `selectCount` | 4 | 计数，返回 `Long` |
| `selectByIds` | 1 | 批量按主键查，3.5.7 起替代 `selectBatchIds` |

以下方法在生产**未使用或不可用**，不要照着教程往项目里套：

| 方法 | 状态 |
| --- | --- |
| `selectPage` | 未使用。项目没有注册分页插件，分页一律手写 `LIMIT / OFFSET`，见 07 号模块 |
| `selectMaps`、`selectObjs` | 未使用。需要投影时写 `@Select` 返回 record，见 07 号模块 |
| `deleteById`、`deleteBatchIds` | 未使用。所有删除都走带 `version` 的手写 `softDelete` |
| `delete`、`deleteByMap`、`update`、`selectByMap`、`exists` | 未使用 |

另外三件事一起记住：生产**不用 XML mapper**（零个 `*.xml`，五十八条 SQL 全在注解里）、**不用 `IService` / `ServiceImpl`**（`BaseMapper` 直接注入 `*RepoImpl`）、**单数据源**（没有动态数据源，也没有分库分表）。

## 对应生产项目的位置

| 这里的概念 | 生产项目的用法 |
| --- | --- |
| `@TableName("svc_task")` | `infrastructure/database/model/TaskDO.java`，表名与这里完全一致 |
| `@TableId(type = IdType.AUTO)` | 所有实体继承的 `BaseModel` |
| `BaseMapper` 接口 | `infrastructure/database/mapper/` 下十四个 Mapper，十三个带注解方法，`RoleMapper` 是唯一空接口 |
| `@MapperScan` | `config/PersistenceConfiguration.java` 第 16 行 |
| `LambdaQueryWrapper` | `infrastructure/repository/` 下的 `*RepoImpl`，实测十一个文件、二十四处调用 |

| `updateById` 整行更新 | `TaskAdapter.taskRow()` 映射出整行 DO，`FieldStrategy` 决定列 |

`*Adapter` 是另一层的名字：它们是 MapStruct 接口（`@Mapper` 来自 `org.mapstruct`），负责 BO 与 DO 互转，里面没有任何 `Wrappers` 调用。把 `Adapter` 和 `RepoImpl` 混为一谈，是读生产代码时最常见的误判。

## 行数说明

五个类合计九十六行（不含注释，含 `package` 与 `import`）。其中 `Lab05Config` 是本次新增的配置类，正文只是一个空类，两行注解；`TaskDO`、`TaskMapper`、`TaskRepository`、`Lab05Application` 四个类承担全部演示。略超八十行纪律的部分是 `Lab05Application` 的打印语句，它们让每个方法的返回值一眼可见。

## 自测三问

第一，如果删掉 `Lab05Config` 同时又删掉 `TaskMapper` 上的 `@Mapper`，启动会报什么错，为什么？

第二，`selectCount` 返回 `Long`，直接把结果赋给 `long` 在什么情况下会抛异常，生产为什么敢这么写？

第三，为什么生产十个删除入口全都不用 `deleteById`，而是各自写一条带 `version` 的 `@Update`？
