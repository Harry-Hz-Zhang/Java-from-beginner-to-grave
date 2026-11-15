package com.harry.lab.mvc;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * 演示二：一个最小的 REST 控制器，把路由、入参解析、返回值序列化、分页参数校验讲清。
 *
 * <p>【@RestController 是什么】它等于 @Controller 加 @ResponseBody。@Controller 只表示这是 Web 组件，
 * 默认返回值被当作视图名；加了 @ResponseBody 才表示把返回值序列化成 JSON 写进响应体。
 *
 * <p>【路径分两层写】类上的 @RequestMapping 放版本前缀与公共的 produces，方法上的 @PostMapping
 * 只写剩余路径和 consumes。将来升版本只改类上一行。生产 controller/task/TaskRunController.java:28-32
 * 就是类上写 produces、方法上写 consumes 并返回 202 的写法。
 *
 * <p>【入参来源】第一，@PathVariable 取路径段。第二，@RequestBody 把 JSON 反序列化成对象。
 * 第三，@RequestParam 取 ?key=value 查询串，list 方法用它，并配 @Min/@Max 约束边界。
 * 第四，@Valid 必须紧贴参数，否则实体上的校验注解不生效。
 *
 * <p>【为什么返回 WebResponse 而不是裸对象】第一，生产普通接口一律包 WebResponse，
 * 成功与失败结构一致。第二，HTTP 状态码由 @ResponseStatus 或异常处理决定，
 * 不用在方法体里手写 ResponseEntity。第三，只有流式接口才用 ResponseEntity，因为要设置 SSE 的头。
 *
 * <p>【创建为什么是 202】生产的任务创建是异步的：接口返回时任务只是排进队列，还没真正执行。
 * 202 Accepted 表示请求已受理但处理尚未完成，见 controller/task/TaskRunController.java:28-32。
 */
@RestController
@RequestMapping(value = "/api/v1/tasks", produces = "application/json")
public class TaskController {

    /** 用内存 Map 代替数据库，让这个 demo 只聚焦 Web 层。真实项目这里注入的是 Service。 */
    private final Map<String, CreateTaskRequest> store = new java.util.concurrent.ConcurrentHashMap<>();

    /** 创建任务。consumes 限定请求体必须是 JSON，传错类型会得到 415 而不是 400。 */
    @PostMapping(consumes = "application/json")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public WebResponse<Map<String, String>> create(@Valid @RequestBody CreateTaskRequest request) {
        var id = UUID.randomUUID().toString().substring(0, 8); store.put(id, request);
        return WebResponse.success(Map.of("id", id, "name", request.name()));
    }

    /** 查询详情。找不到时抛业务异常，由全局异常处理转成 404 与统一结构。 */
    @GetMapping("/{id}")
    public WebResponse<CreateTaskRequest> detail(@PathVariable String id) {
        var found = store.get(id); if (found == null) throw BusinessException.notFound("任务不存在: " + id);
        return WebResponse.success(found);
    }

    /** 列表。参数级校验抛 HandlerMethodValidationException，不是 MethodArgumentNotValidException。 */
    @GetMapping
    public WebResponse<List<CreateTaskRequest>> list(
            @RequestParam(defaultValue = "0") @Min(0) @Max(1000000) int offset, @RequestParam(defaultValue = "20") @Min(1) @Max(200) int limit) {
        return WebResponse.success(store.values().stream().skip(offset).limit(limit).toList());
    }
}
