# 08 数据库版本化迁移

## 演示什么

五个 SQL 文件如何随应用启动自动执行、为什么「已经执行过的脚本一个字都不能改」、以及生产加非空列的三步法。这个模块的重点在 SQL 文件，不在 Java 代码。

## 怎么运行

```bash
mvn -pl 08-flyway-migration spring-boot:run
```

真实输出。

```
已应用的迁移：
  V1 / create task / 状态=SUCCESS
  V2 / add model column / 状态=SUCCESS
  V3 / add active name index / 状态=SUCCESS
  V4 / backfill reasoning effort / 状态=SUCCESS
  V5 / seed tasks / 状态=SUCCESS
种子数据 2 行（V5 只执行一次，重启不会重复插入）：
  uuid-1 / 镜像同步 / deepseek-chat / OFF
  uuid-2 / 日志归档 / deepseek-reasoner / MEDIUM
```

**再跑一次，输出完全相同**。五条迁移都还列在那里，但一条都没有重新执行；种子数据仍是两行，没有撞唯一键。这就是「版本表记录已执行状态」的直接证据。

## 为什么必须是文件库

`application.yaml` 里写的是 `jdbc:h2:file:./target/lab08`，不是内存库。原因是内存库每次重启都是一张白纸，`flyway_schema_history` 跟着一起消失，Flyway 每次都当作第一次启动，校验和永远不会真正参与比对。用内存库也能看到「五条迁移执行成功」，但「改脚本就报错」这个最关键的演示根本演不出来。

文件库的代价是状态会留在 `target/lab08.mv.db`，想从头再演一遍要先删掉它。

## 验证校验和机制

第一次启动只执行、不比对，历史表里还没有可比对的对象。**改完脚本，第二次启动才会校验**。

把 `V1__create_task.sql` 里任意一个字符改掉（比如把 `varchar(16)` 改成 `varchar(17)`），再运行一次，Spring 启动会失败，报错里是：

```
Migration checksum mismatch for migration version 1
```

改回来就能恢复。生产环境里这条规则意味着：需要调整结构就新增 V6，永远不要回头改 V1。

如果某次迁移半路失败（比如 SQL 语法错、唯一键冲突），Flyway 会在历史表里留下一条 `success = 0` 的记录，应用下次启动**不会自动修复**，会一直卡在这条失败记录上。处理方式是人工介入：修好 SQL 或回退数据，然后执行 `flyway repair` 清掉失败记录，再启动。生产 V11 的注释里就明确写了这个流程。

## 关键语法点

第一，文件名格式是 `V{版本}__{描述}.sql`，版本与描述之间是**两个**下划线。

第二，版本号决定执行顺序。生产十二个脚本全部是单段整数 `V1` 到 `V12`，没有点分也没有下划线分段。`V2_1` 里的下划线是「版本段分隔符」，含义是 `2.1`，不是常见写法；真要用点分请写 `V2.1`，团队里能不用就不用。

第三，已执行脚本的校验和会被记录，改动会导致启动失败，且第二次启动才比对。

第四，`flyway_schema_history` 是状态权威，每次启动先读它再决定执行什么。

第五，`validate-on-migrate` 开启校验和检查，`validate-migration-naming` 检查文件名规范，`clean-disabled` 禁止清库。

第六，**给已有数据的表加非空列不是「必须带默认值」，生产用的是三步法**：先 `ADD COLUMN ... DEFAULT NULL`，再 `UPDATE ... WHERE ... IS NULL` 回填，最后 `MODIFY COLUMN ... NOT NULL`。见 V4 与生产 V11。带默认值能一条语句跑通，但会把默认值永久写进表定义，之后每次插入都悄悄套用。

第七，新增列一律带 `AFTER 某列`，固定物理顺序，避免不同环境 dump 出来的列顺序不同。生产 V11 的全部新增列都这么写。

第八，`Flyway` 的 `info()`、`validate()`、`repair()` 是跨方言的，比自己拼 SQL 查历史表稳。但要说清楚：**生产 main 代码根本不读历史表**，只有集成测试注入 `Flyway` 调这几个方法，运行时完全依赖「启动自动迁移」这一条路径。

## 完整用法清单：V1 到 V12 用了哪些 DDL 特性

| 版本 | 内容 | 用到的特性 |
| --- | --- | --- |
| V1 | 建身份与权限四张表 | 建表、普通唯一键、函数唯一索引、CHECK、复合索引 |
| V2 | 灌固定角色 | 纯 DML（`INSERT INTO ... VALUES`） |
| V3 | 灌唯一超级管理员 | 纯 DML，两条 INSERT |
| V4 | 用户邮箱改可空 | 单列 `ALTER TABLE ... ADD COLUMN`、列 `COMMENT` |
| V5 | 建项目管理三张表 | 建表、唯一键、索引、CHECK |
| V6 | 建运行时表 | 建表、唯一键、索引 |
| V7 | 建任务与运行记录表 | 建表、`json` 列、函数唯一索引、CHECK、索引、两条 `ALTER` |
| V8 | 建镜像表 | 建表、函数唯一索引、CHECK、索引 |
| V9 | 建运行记录三张表 | 建表、`json` 列、CHECK、索引 |
| V10 | 任务加空闲时间列 | `ALTER TABLE ADD COLUMN`、加索引、加 CHECK、`UPDATE` 回填 |
| V11 | 任务模型配置改造 | 临时表守卫、`DROP CHECK`、`DROP COLUMN`、多列 `ADD COLUMN ... AFTER`、`UPDATE` 回填、`MODIFY COLUMN ... NOT NULL`、`ADD CONSTRAINT` |
| V12 | 建镜像拉取记录表 | 建表、唯一键、索引、CHECK |

十二个脚本全是「新增文件」演进，没有一处回头修改旧脚本。

## 对应生产项目的位置

| 这里的概念 | 生产项目的用法 |
| --- | --- |
| 迁移脚本目录 | `harness_saas/src/main/resources/db/migration/`，V1 到 V12 共十二个脚本 |
| 配置项 | `application.yaml` 的 `spring.flyway` 段，三个开关与本模块一致 |
| 脚本演进方式 | V1 建身份表、V8 建镜像表、V11 加模型配置，全部新增而非修改 |
| 三步法加非空列 | V11 的 `ADD COLUMN ... DEFAULT NULL` → `UPDATE` → `MODIFY ... NOT NULL` |
| 函数唯一索引 | V8 第 20 到 21 行、V7 第 67 到 68 行、V1 第 74 行 |
| 方言支持 | 额外引入 `flyway-mysql` 处理 MySQL 专有语法 |

H2 与 MySQL 有两处差异，本模块用等价写法绕开，读脚本时要知道：

第一，H2 不支持逗号分隔的多列 `ADD COLUMN`，所以 V2 拆成三条单列语句，`AFTER` 与生产一致。

第二，H2 不支持表达式索引，所以 V3 用「生成列 + 普通唯一索引」表达生产那条件函数唯一索引的语义：`active_name` 只在 `deleted = 0` 时有值，多行 NULL 在唯一索引里互不冲突。

## 行数说明

一个类三十一行，在纪律之内。这个模块的重点是五个 SQL 文件，Java 只负责把迁移状态和种子数据打印出来。为了让第二次启动不撞唯一键，种子数据从 Java 挪进了 V5，代码反而比之前更短。

## 自测三问

第一，如果把数据源改回内存库，第二次启动时「改 V1 报校验和错误」还能复现吗，为什么？

第二，V4 如果改成 `ADD COLUMN reasoning_effort varchar(32) NOT NULL DEFAULT 'OFF'`，能跑通吗，与三步法相比语义上差在哪？

第三，一条迁移执行到一半失败，历史表里会留下什么，为什么应用不会自动恢复，正确的人工处理顺序是什么？
