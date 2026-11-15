# 04 声明式事务

## 演示什么

这个模块用六次转账，把 `@Transactional` 的生效条件、失效场景、回滚规则，以及编程式事务的用法一次演示完。它不依赖你安装 MySQL，用的是 H2 内存库。

## 怎么运行

```bash
mvn -pl 04-transaction spring-boot:run
```

真实输出如下。

```
[初始] A=1000 B=0
捕获: 单笔限额 100，事务应当回滚
[声明式 + 运行时异常（应回滚）] A=1000 B=0
捕获: 单笔限额 100，事务应当回滚
[同类自调用（注解失效，已提交）] A=850 B=150
捕获: 受检异常，默认不回滚
[受检异常 + 不写 rollbackFor（已提交）] A=700 B=300
捕获: 受检异常，显式要求回滚
[受检异常 + rollbackFor（应回滚）] A=700 B=300
[编程式 + setRollbackOnly（应回滚）] A=700 B=300
[事务后] 已提交，可以安全投递异步任务
[编程式 + 正常提交（触发提交后回调）] A=650 B=350
```

判断方法只有一个：该回滚的一步，余额必须不变。第一、四、五步余额没变，说明回滚生效；第二步余额变了，说明自调用绕过了代理；第三步余额也变了，说明受检异常默认不回滚。

## 关键语法点

第一，`@Transactional` 的实现方式是动态代理。Spring 给这个 Bean 生成代理对象，调用方拿到的是代理，代理在真实方法前后插入开启、提交、回滚。

第二，由此推出两个限制：只有通过代理调用才生效，方法必须是 `public`。同类内部自调用（`this.transfer(...)`）绕过代理，事务注解完全失效。

第三，默认只对 `RuntimeException` 及其子类回滚。方法抛受检异常时事务照常提交，这是线上事故的常见来源。跨 Bean 调用、注入自身代理、改用 `TransactionTemplate`，是自调用的三种正解；本模块的 `AuditService` 演示第一种。

第四，生产并不要求写 `rollbackFor`。生产 29 处 `@Transactional` 里 `rollbackFor` 是 0 处，因为所有业务异常都继承 `RuntimeException`，默认规则就够。只有确实要抛受检异常时才需要显式声明，本模块的 `transferCheckedRollback` 是对照。

第五，`readOnly = true` 用于查询方法，既是性能提示也是契约声明。生产只有 `application/ProjectApp.java:44` 一处，`service` 包零处。

第六，`isolation` 与 `timeout` 是生产写得最多的两个属性：28 处显式指定 `READ_COMMITTED`，17 处指定 `timeout = 30`。超时的意义是避免一次写事务长期占住连接。

第七，编程式事务用 `TransactionTemplate`：`setIsolationLevel` 与 `setTimeout` 显式配置，`execute(status -> {...})` 里只放短写操作。长 IO（本模块用 `Thread.sleep` 模拟）必须留在模板外。

第八，没有异常也能主动回滚：`status.setRollbackOnly()` 把事务标记为只能回滚，提交时不会真正写入。

第九，事务提交后再起异步：用 `TransactionSynchronizationManager.registerSynchronization` 注册回调，在 `afterCompletion` 里判断 `STATUS_COMMITTED` 再投递。直接在事务里提交异步任务，异步线程可能读到未提交的数据。

## 完整用法清单：事务属性对照

| 属性 | 生产取值 | 出现次数 | 本 demo 是否演示 |
| --- | --- | --- | --- |
| `isolation` | `Isolation.READ_COMMITTED` | 28 / 29 | 是，声明式与编程式都写了 |
| `timeout` | `30` | 17 / 29 | 是，声明式与编程式都写了 |
| `readOnly` | `true` | 1 / 29（`ProjectApp.java:44`） | 是，`balance` 方法 |
| `rollbackFor` | 无 | 0 / 29 | 是对照组，演示不写与写上的差别 |
| `propagation` | 无，全部默认 `REQUIRED` | 0 / 29 | 是，`transfer` 调 `AuditService.record` 共用同一事务 |

## 完整用法清单：声明式与编程式

第一，声明式适合短小、边界清晰的写方法，注解一贴就生效，代码里看不到事务相关语句。

第二，编程式适合「长流程里只包一小段写操作」的场景。生产 `application/TaskApp.java:38,52-54,154-166` 就是注入 `PlatformTransactionManager` 构造 `TransactionTemplate`，长流程在模板外，短写操作在 `execute` 里。

第三，两者可以混用，但参数要一致。本模块的模板也设了 `READ_COMMITTED` 与 30 秒超时，避免同一业务出现两套隔离级别。

第四，编程式的另一个好处是回滚条件可以写得更细：`setRollbackOnly()` 允许「没有异常但业务判定失败」时回滚，声明式只能靠抛异常。

## 自测三问

第一，把 `transferWrong` 改成在 `AuditService` 里调用一次 `transfer`，事务会生效吗？会。跨 Bean 调用拿到的是被调用方的代理，注解正常生效。这正是「拆到另一个 Bean」成为自调用正解的原因。

第二，把 `transferCheckedRollback` 的 `rollbackFor` 删掉会怎样？和 `transferChecked` 一样，异常抛出但两笔更新照常提交，余额会变化。这就是受检异常必须显式声明的场景。

第三，把 `transferProgrammatic` 里的 `setRollbackOnly` 删掉会怎样？金额 150 超过限额，但没有异常，事务会提交，余额从 700/300 变成 550/450，同时提交后回调会打印。这说明回滚不能只依赖异常。

## 对应生产项目的位置

| 这里的概念 | 生产项目的用法 |
| --- | --- |
| `@Transactional` 加在写方法上 | `harness_saas/application` 下 8 个类共 29 处，`TaskRunExecutor` 与 `TaskRuntimeApp` 零处 |
| `isolation` 与 `timeout` | 28 处 `READ_COMMITTED`，17 处 `timeout = 30` |
| `readOnly = true` | `application/ProjectApp.java:44`，全仓唯一一处 |
| `rollbackFor` | 0 处，业务异常全部继承 `RuntimeException` |
| 编程式事务 | `application/TaskApp.java:38,52-54,154-166`，`TransactionTemplate` 只包短写操作 |
| 提交后回调 | `application/TaskRunApp.java:51-60`，`afterCompletion` 判断 `STATUS_COMMITTED` 后再提交异步 |
| 跨 Bean 事务方法共用事务 | 全部 29 处都靠跨 Bean 调用，没有同类自调用 |
| 补偿而不是同事务 | `application/TaskApp.java:65-84` 的 `create` 没有 `@Transactional`，第二步失败时用 `softDelete` 补偿第一步 |

最后一行值得单独说明：创建任务要写 `svc_task`，还要创建工作区与 Skills。生产没有把整段放进一个事务，而是先建任务记录，后续步骤失败时调用 `softDelete` 回滚已写入的部分。原因是工作区涉及外部存储，长操作放在事务里会长时间占用连接。

## 行数说明

三个类合计八十三行业务代码（注释不计），超出七十行。超出部分对应清单要求的六项能力：声明式回滚、自调用失效、受检异常两种对照、编程式模板与主动回滚、提交后回调、跨 Bean 共用事务。压到七十行必须删掉其中一项，这里选择保留完整的对照。
