package com.harry.lab.audit;

import java.util.Map;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 依次验证五件事，每一步的打印结果都能证明框架背着你做了事。
 *
 * <p>第一，插入后 version=0、deleted=false、createdAt 有值，而这三行代码你一处都没写，
 * 说明 MetaObjectHandler 生效了。createdBy=1 是你自己 set 的，拦截器刻意不填它。
 *
 * <p>第二，updateById 之后 version 变成 1，说明乐观锁拦截器把 version 拼进了 WHERE 并自增。
 *
 * <p>第三，new 一个只有 id 的对象直接 updateById，systemPrompt 被置空、taskName 保留旧值。
 * 前者标了 ALWAYS，后者走默认的 NOT_NULL，这就是字段策略在起作用。
 *
 * <p>第四，两个同 version 的对象连续更新同一行，第一次返回 1，第二次返回 0。
 * 生产据此抛 BusinessException.conflict()，见 TaskRepoImpl.java 第 54 行。
 *
 * <p>第五，手写 softDelete 之后 selectById 返回 null，但用 JdbcTemplate 直接查库
 * 能看到那一行还在，只是 deleted 变成了 1。逻辑删除对所有 BaseMapper 方法透明，
 * 必须绕过框架才能看见底层数据。
 */
@SpringBootApplication
public class Lab06Application implements CommandLineRunner {

    private final TaskMapper mapper;
    private final JdbcTemplate jdbc;

    public Lab06Application(TaskMapper mapper, JdbcTemplate jdbc) {
        this.mapper = mapper;
        this.jdbc = jdbc;
    }

    @Override
    public void run(String... args) {
        var task = newTask("uuid-1", "镜像同步", "默认身份");
        mapper.insert(task);
        System.out.println("插入后 version=" + task.getVersion() + " deleted=" + task.getDeleted()
                + " createdBy=" + task.getCreatedBy() + " createdAt=" + task.getCreatedAt());

        task.setTaskName("镜像同步-已改名");
        mapper.updateById(task);
        System.out.println("更新后 version=" + task.getVersion());

        var clear = new TaskDO();
        clear.setId(task.getId());
        mapper.updateById(clear);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT task_name, system_prompt FROM svc_task WHERE id = ?", task.getId());
        System.out.println("清空后 system_prompt=" + row.get("system_prompt")
                + " task_name=" + row.get("task_name"));

        var second = newTask("uuid-2", "日志归档", null);
        mapper.insert(second);
        System.out.println("同版本连改两次 = " + conflict(second.getId()));

        var version = mapper.selectById(second.getId()).getVersion();
        System.out.println("softDelete 影响行数 = " + mapper.softDelete(second.getId(), version, "key-1"));
        System.out.println("删除后 selectById = " + mapper.selectById(second.getId()));
        System.out.println("库里 deleted = " + jdbc.queryForObject(
                "SELECT deleted FROM svc_task WHERE id = ?", Integer.class, second.getId()));
    }

    private TaskDO newTask(String uuid, String name, String prompt) {
        var task = new TaskDO();
        task.setTaskUuid(uuid);
        task.setTaskName(name);
        task.setSystemPrompt(prompt);
        task.setCreatedBy(1L);
        task.setUpdatedBy(1L);
        return task;
    }

    private TaskDO patch(Long id, String name) {
        var row = new TaskDO();
        row.setId(id);
        row.setVersion(0L);
        row.setTaskName(name);
        return row;
    }

    private String conflict(Long id) {
        var first = mapper.updateById(patch(id, "第一次改名"));
        var second = mapper.updateById(patch(id, "第二次改名"));
        return first + " / " + second;
    }

    public static void main(String[] args) {
        SpringApplication.run(Lab06Application.class, args);
    }
}
