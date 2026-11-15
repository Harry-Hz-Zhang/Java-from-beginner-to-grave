# 03 Web 接入与参数校验

## 演示什么

这个模块演示一次 HTTP 请求在 Spring MVC 里经过的环节：路由匹配与参数绑定、参数校验、异常转换成响应。所有接口都返回统一的 `WebResponse`，错误一律由 `@RestControllerAdvice` 收敛。它不连数据库，所以你能专心看 Web 层本身。

## 怎么运行

```bash
mvn -pl 03-mvc-validation spring-boot:run
```

另开一个终端执行下面的命令，每一条对应一种结果。

```bash
# 1. 正常创建：202，任务被受理（生产是异步执行，所以不是 201）
curl -i -X POST localhost:8080/api/v1/tasks \
  -H 'Content-Type: application/json' \
  -d '{"name":"镜像同步","replicas":2}'

# 2. 名字为空：400，INVALID_REQUEST
curl -i -X POST localhost:8080/api/v1/tasks \
  -H 'Content-Type: application/json' \
  -d '{"name":"","replicas":2}'

# 3. 多传一个未知字段：400，fail-on-unknown-properties 打开后不会被静默忽略
curl -i -X POST localhost:8080/api/v1/tasks \
  -H 'Content-Type: application/json' \
  -d '{"name":"x","replicas":1,"bogus":1}'

# 4. 分页参数越界：400，走参数级校验分支
curl -i 'localhost:8080/api/v1/tasks?offset=-1'

# 5. 用第 1 步返回的 id 查询：200
curl -i localhost:8080/api/v1/tasks/<把第1步的id粘这里>

# 6. 查询不存在的 id：404，NOT_FOUND
curl -i localhost:8080/api/v1/tasks/not-exist

# 7. 方法不支持：405
curl -i -X DELETE localhost:8080/api/v1/tasks

# 8. 请求体不是 JSON：415
curl -i -X POST localhost:8080/api/v1/tasks -H 'Content-Type: text/plain' -d 'x'
```

## 关键语法点

第一，`@RestController` 等于 `@Controller` 加 `@ResponseBody`，返回值被序列化成 JSON 而不是去找视图模板。

第二，路径分两层写，类上的 `@RequestMapping` 放版本前缀和公共的 `produces`，方法上的 `@PostMapping` 放剩余路径和 `consumes`。

第三，入参来源用不同注解区分：`@PathVariable` 取路径段，`@RequestBody` 取请求体，`@RequestParam` 取查询串。本模块的 `list` 方法用 `@RequestParam` 配 `@Min`/`@Max` 做分页边界校验，这段校验由方法参数级校验器执行。

第四，`@Valid` 必须写，而且要紧贴参数。不写它，实体上的校验注解全部不生效，也不会报任何错。

第五，`@NotBlank`、`@NotEmpty`、`@NotNull` 三者语义不同：第一个拦 null 与空串与纯空格，第二个拦 null 与长度零，第三个只拦 null。

第六，返回类型统一是 `WebResponse<T>`。HTTP 状态码由 `@ResponseStatus` 或异常处理决定，方法体内不手写 `ResponseEntity`。生产普通接口一律包 `WebResponse`，`ResponseEntity` 只用于流式响应（要设置 SSE 的头）。

第七，`@RestControllerAdvice` 配合 `@ExceptionHandler` 做全局异常收敛，匹配顺序是子类优先。只用 `Exception` 兜底会把编程错误伪装成业务错误，所以每个具体类型都要有分支。

第八，`@ResponseStatus` 决定成功响应的状态码。本模块创建接口用 202 Accepted，表示请求已受理但处理尚未完成。

第九，请求对象里的可选数值字段用包装类型 `Integer` 配 `@NotNull`、`@PositiveOrZero`。基本类型 `int` 在字段缺失时等于 0，会把「没传」误判成「传了 0」。

## 完整用法清单：HTTP 方法、参数来源与返回类型

| 场景 | HTTP 方法 | 参数来源 | 返回类型 |
| --- | --- | --- | --- |
| 创建任务 | `POST` + `consumes = application/json` | `@RequestBody` + `@Valid` | `WebResponse<Map<String, String>>`，202 |
| 查询详情 | `GET` | `@PathVariable` | `WebResponse<CreateTaskRequest>` |
| 查询列表 | `GET` | `@RequestParam` + `@Min`/`@Max` | `WebResponse<List<CreateTaskRequest>>` |
| 流式订阅（生产） | `POST` + `produces = text/event-stream` | `@RequestBody` | `ResponseEntity<SseEmitter>` |

## 完整用法清单：异常类型、状态码与错误码

| 异常 | 状态码 | 响应里的 code |
| --- | --- | --- |
| `MethodArgumentNotValidException` | 400 | `INVALID_REQUEST` |
| `HandlerMethodValidationException` | 400 | `INVALID_REQUEST` |
| `HttpMessageNotReadableException` | 400 | `INVALID_REQUEST` |
| `MethodArgumentTypeMismatchException` | 400 | `INVALID_REQUEST` |
| `BusinessException(NOT_FOUND)` | 404 | `NOT_FOUND` |
| `BusinessException(VERSION_CONFLICT)` | 409 | `VERSION_CONFLICT` |
| `HttpRequestMethodNotSupportedException` | 405 | `INVALID_REQUEST` |
| `HttpMediaTypeNotSupportedException` | 415 | `INVALID_REQUEST` |
| 其他 `Exception` | 500 | `INTERNAL_ERROR` |

第二行是最容易漏的一项：`@RequestParam` 上的 `@Min`/`@Max` 抛的是 `HandlerMethodValidationException`，不是 `MethodArgumentNotValidException`。漏掉这一支不会变成 400，会落到兜底分支变成 500。

## 自测三问

第一，把 `GlobalExceptionHandler` 里的 `HandlerMethodValidationException` 分支删掉，`?offset=-1` 会返回什么？会落到 `Exception` 兜底，变成 500 加 `INTERNAL_ERROR`。这就是参数级校验和请求体校验必须分开处理的原因。

第二，把 `CreateTaskRequest.replicas` 改回 `int` 会怎样？不传 `replicas` 时它是 0，`@PositiveOrZero` 通过，接口会在缺少必填信息的情况下成功。改成 `Integer` 加 `@NotNull` 才能拦住。

第三，`spring.jackson.deserialization.fail-on-unknown-properties` 关掉会怎样？多传的字段被静默丢弃，前后端字段名写错也看不出来。打开后多传字段直接 400。

## 对应生产项目的位置

| 这里的概念 | 生产项目的用法 |
| --- | --- |
| `@RestController` 与版本前缀 | `harness_saas/controller/` 下 15 个控制器，统一 `/api/v1` 前缀，服务端接口走 `/api/service/v1` |
| 请求体校验 | `controller/request/` 下每个请求对象，例如 `CreateRunRequest` |
| 统一响应 | `controller/vo/WebResponse.java:3-10`，`code`/`message`/`data` 加两个静态工厂 |
| 错误码 | `domain/iam/ErrorCode.java:3-34`，每个枚举带 `httpStatus` |
| 业务异常 | `domain/iam/BusinessException.java:3-13`，继承 `RuntimeException`，提供 `notFound()` 等工厂 |
| 统一异常与错误码 | `controller/GlobalExceptionHandler.java`，用 `@ExceptionHandler` 数组按语义合并类型 |
| 异步资源返回 202 | `controller/task/TaskRunController.java:28-32` |
| 分页参数校验 | `controller/task/TaskController.java:31-37`，`offset` 与 `limit` 都带上下界 |
| `ResponseEntity` 控制响应 | `TaskRunController.stream` 返回 `ResponseEntity<SseEmitter>`，因为要设置流式响应的头 |

## 行数说明

六个类合计九十行业务代码（注释不计），比七十行的常规纪律多二十行的原因是：`WebResponse`、`ErrorCode`、`BusinessException` 三个类型是生产统一响应的最小结构，`GlobalExceptionHandler` 的六个分支各对应一种真实错误路径，删掉任何一支都会让某一类错误变成 500。
