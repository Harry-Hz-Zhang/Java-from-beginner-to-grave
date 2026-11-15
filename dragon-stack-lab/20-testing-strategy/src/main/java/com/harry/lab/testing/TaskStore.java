package com.harry.lab.testing;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 演示二：控制器的唯一依赖，用内存 Map 代替数据库。
 *
 * <p>【第一，为什么抽一个 Bean 出来】控制器自己不存数据，它只做三件事：
 * 收参数、调依赖、装信封。把存储抽成独立 Bean 之后，测试可以只替换这一个依赖，
 * 而不需要真的连数据库。生产项目里这个角色是 repository 接口，
 * 测试时用 @MockitoBean 或 @MockitoSpyBean 替换。
 *
 * <p>【第二，为什么用 Optional 表达「没找到」】返回 null 会让调用方忘记判空，
 * Optional 把「可能没有」写进签名，编译器的类型检查会提醒你处理两种情况。
 * 控制器里的 404 分支就是从这里来的。
 *
 * <p>【第三，返回值是视图对象而不是存储行】TaskView 只有 id 和 name，
 * 没有创建时间、版本号这类内部字段。接口的契约从这一层就开始收窄。
 */
@Component
public class TaskStore {

    private final Map<String, TaskView> rows = new ConcurrentHashMap<>();

    public Optional<TaskView> find(String id) {
        return Optional.ofNullable(rows.get(id));
    }

    public TaskView create(String name) {
        var view = new TaskView(UUID.randomUUID().toString(), name);
        rows.put(view.id(), view);
        return view;
    }

    public record TaskView(String id, String name) {
    }
}
