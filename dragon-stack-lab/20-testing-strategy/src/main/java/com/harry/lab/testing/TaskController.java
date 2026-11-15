package com.harry.lab.testing;

import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 控制器本身很简单，价值在于给测试提供三种可验证的行为：
 * 正常返回、目标不存在、请求体校验失败。
 *
 * <p>【第一，为什么只留三种行为】它们覆盖了控制器里的全部分支：成功路径、
 * 业务失败路径、框架层面的校验路径。真实项目里控制器不该有更多逻辑，
 * 复杂的部分属于服务层，那里用纯单元测试覆盖，不需要启动 Spring。
 *
 * <p>【第二，状态码与业务码各管一段】404 用 HTTP 状态码表达，同时信封里的 code
 * 给出 TASK_NOT_FOUND。调用方既可以按状态码做统一处理，也可以按业务码区分具体原因。
 *
 * <p>【第三，校验失败由框架返回 400】方法参数上的 @Validated 加 @NotBlank
 * 让 Spring 在进入方法体之前就拒绝请求，所以控制器代码里看不到校验语句。
 * 测试断言 400 就足以证明校验生效，不必断言具体文案。
 */
@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {

    private final TaskStore store;

    public TaskController(TaskStore store) {
        this.store = store;
    }

    @GetMapping("/{id}")
    public ResponseEntity<WebResponse<TaskStore.TaskView>> detail(@PathVariable String id) {
        return store.find(id)
                .map(view -> ResponseEntity.ok(WebResponse.success(view)))
                .orElseGet(() -> ResponseEntity.status(404)
                        .body(WebResponse.error("TASK_NOT_FOUND", "任务不存在")));
    }

    @PostMapping
    public ResponseEntity<WebResponse<TaskStore.TaskView>> create(
            @Validated @RequestBody CreateRequest request) {
        return ResponseEntity.status(201).body(WebResponse.success(store.create(request.name())));
    }

    public record CreateRequest(@NotBlank(message = "名称不能为空") String name) {
    }
}
