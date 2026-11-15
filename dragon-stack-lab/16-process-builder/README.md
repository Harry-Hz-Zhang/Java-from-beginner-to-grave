# 16 调用外部命令

## 演示什么

用 `ProcessBuilder` 调用 `docker` 这类外部命令，并且把六种情况都处理对：正常返回、命令失败、操作冲突、目标缺失、执行超时、长驻进程优雅终止。runner 里所有容器操作最终都落在这一个类上。

## 怎么运行

```bash
mvn -pl 16-process-builder spring-boot:run
```

真实输出。

```
容器 Id   = abc123
容器状态  = running
失败分支  = [DOCKER_UNAVAILABLE] 命令失败，退出码 1: Cannot connect to the Docker daemon
冲突分支  = [CONFLICT] 命令失败，退出码 1: Conflict. container is running
缺失分支  = [NOT_FOUND] 目标容器已不存在: Error: No such container: abc123
超时分支  = [TIMEOUT] 命令超时 2 秒: [sh, -c, sleep 10]
长驻进程  = waitFor(2s)→destroy→1 秒内退出，退出码 143，耗时 2011 毫秒
```

后三条的退出码都是 1，唯一区别在 stderr 内容，所以必须按 stderr 分类，不能只看退出码。

## 为什么这里调 `sh` 而不是真的 `docker`

因为一旦真调 `docker`，这个 demo 就从「学 ProcessBuilder」变成了「学 Docker 权限配置」。本机没装 Docker、当前用户不在 docker 组、镜像不存在，任何一条都会让 demo 跑不起来，而你根本分不清是代码错了还是环境错了。

用 `sh -c "echo ..."` 模拟，命令字符串的结构和真调 docker 完全一样，只是第一个元素换了。生产的 `ProcessDockerCommandRunner` 里就是 `List.of("docker", "image", "inspect", ...)`。

## 关键语法点

**第一，生产从不合并 stderr。** 全仓 grep 不到 `redirectErrorStream`。它不是「需要区分时才用两个线程」，而是**永远**开两个虚拟线程分别 drain `getInputStream()` 和 `getErrorStream()`（`ProcessDockerCommandRunner:34-37`）。理由有两个：一是合并之后无法区分 docker 的普通警告和真正的失败；二是错误分类要靠 stderr 文本（见第五条），合并了就分不出来。

**第二，必须并发读，`先 waitFor 再读` 必然死锁。** 子进程的 stdout/stderr 是两根容量有限的管道（Linux 上常见 64KB）。写满后子进程的 `write` 阻塞，它永远退不出；父进程在 `waitFor` 里等它退出，两边互等。只要输出超过管道容量就一定发生，小输出时完全测不出来。

**第三，必须有字节上限。** 并发读解决了死锁，但 `docker build`、`docker logs` 这类命令输出几十 MB，全塞内存就是 OOM。生产的做法是 stdout 留 1MB、stderr 留 16KB（`:19-20`），超过上限后继续读但不再保存，并置 `truncated` 标记（`:84-86`）。调用方看到 `truncated` 就知道结果不完整，该抛 `PROTOCOL_ERROR`，而不是拿半截 JSON 去解析（`DockerCliContainerGateway:260-262`）。

**第四，`finally` 里的清理必须无条件执行。** 成功路径也要关三个流，否则每次调用泄漏几个文件描述符，跑上千次就报 `Too many open files`。进程还活着就 `destroyForcibly()`（`:56-60`），避免留下孤儿进程。

**第五，必须主动关 stdin。** `docker create`、`container stop` 会等 stdin 读到 EOF 再继续（`:39`）。父进程不关，它就一直等，白等到超时，你还以为是 docker 慢。

**第六，错误必须分类。** 生产有 `TIMEOUT` / `DOCKER_UNAVAILABLE` / `CONFLICT` / `NOT_FOUND` 四种码，派生自两处逻辑：超时单独判定（`:41`），其余按 stderr 分类，`Conflict.` 和 `is running` 归 `CONFLICT`（`DockerCliContainerGateway:290-293`），`No such container:` / `No such object:` 归「可恢复」的缺失分支（`:288`）。混成一个 `IllegalStateException` 的后果是「容器已经被删」被当成 Docker 挂了，上层于是停止整个 runner——这其实是最容易自愈的一种情况。

**第七，长驻进程要按顺序终止。** `waitFor` → `destroy` → `waitFor(1s)` → `destroyForcibly` → `waitFor(2s)` → 再关管道（`DockerCliContainerGateway:410-425`）。先给 SIGTERM 让它有机会收尾，一秒不退再 SIGKILL。顺序反了（先关流再杀进程）会让阻塞在写操作上的子进程拿不到锁，反而关不掉。

**第八，退出码只是分类的输入，不是唯一判据。** 失败、冲突、缺失都是 `exitValue() != 0`，真正的区别在 stderr。

## 完整用法清单

| 写法 | 作用 | 生产项目里的位置 |
| --- | --- | --- |
| `new ProcessBuilder(List<String>)` | 用列表传参，避免 shell 拼接与注入 | `ProcessDockerCommandRunner.start()` |
| `.start()` | 启动子进程 | 同上 |
| `Thread.startVirtualThread(capture)` ×2 | 并发 drain stdout / stderr，防管道死锁 | `:34-37` |
| 字节上限 1MB / 16KB + `truncated` | 防大日志把内存吃光 | `:19-20`、`:84-86` |
| `getOutputStream().close()` | 让 `docker create` / `container stop` 读到 EOF | `:39` |
| `waitFor(t, unit)` | 带超时等待 | `dockerTimeout` / `pullTimeout` 分档 |
| `finally { if (isAlive()) destroyForcibly(); close ×3 }` | 无条件回收进程与文件描述符 | `:56-60` |
| `exitValue() != 0` + stderr 分类 | 区分 TIMEOUT / DOCKER_UNAVAILABLE / CONFLICT / NOT_FOUND | `DockerCliContainerGateway:288-293` |
| `destroy → waitFor(1s) → destroyForcibly → waitFor(2s)` | 长驻进程优雅终止 | `DockerCliContainerGateway:410-425` |
| `container rm`（**不加 `--force`**） | 只删已停止的容器，不打断意外存活的实例 | `:191-192` |

## 自测三问

**问一**：为什么不能 `waitFor` 之后再 `readAllBytes()`？

答：子进程输出超过管道缓冲区（常见 64KB）时，它会在 `write` 上阻塞，永远不退出；父进程在 `waitFor` 里等它退出。两边互等，进程挂死，没有异常也没有日志。本 demo 的失败分支只有几十字节，所以看不出问题——这正是它危险的地方。

**问二**：`finally` 里已经 `destroyForcibly` 了，为什么还要关三个流？

答：`destroyForcibly` 只杀进程，不关父进程这边的文件描述符。**成功路径**下进程已退出，`isAlive()` 为 false，一个流都不会关，全靠最后三行。不关的话每次调用泄漏 fd，长跑必炸。

**问三**：为什么 `No such container:` 不能和 `Cannot connect to the Docker daemon` 用同一个错误码？

答：前者表示目标容器已经不存在，属于可恢复状态（当作已删除处理，继续下一个任务）；后者表示 Docker 本身不可用，属于环境故障（要告警、要停）。归成一个码，上层只能二选一：要么把环境故障当可恢复反复空转，要么把可恢复情况当致命错误整体停摆。

## 对应生产项目的位置

| 这里的概念 | 生产项目的用法 |
| --- | --- |
| 命令执行器 | `harness-runner-core/.../infrastructure/docker/ProcessDockerCommandRunner` |
| 容器创建 | `docker container create ...` |
| 容器启动 | `docker start --attach --interactive <id>` |
| 镜像检查 | `docker image inspect --format ...`，输出是 JSON，本模块 `Lab16Application` 演示了解析 |
| 分档超时 | `RunnerProperties` 里的 `dockerTimeout`、`pullTimeout`、`initializeTimeout`、`requestTimeout`、`waitTimeout`、`stopTimeout` |
| 强制清理 | `remove()` 用 `container rm`，**明确不加 `--force`**（`:191-192`） |
| 优雅终止 | `ProcessChannel.close()`（`:410-425`） |

## 行数说明

两个类合计约一百六十行代码（不含注释）。`CommandRunner` 占了大部分：双流并发、上限、分类、终止四件事各自都需要一个独立分支，合并会牺牲可读性。`Capture` 作为内部类留在同一个文件里，它的存在只服务于 `run()`。
