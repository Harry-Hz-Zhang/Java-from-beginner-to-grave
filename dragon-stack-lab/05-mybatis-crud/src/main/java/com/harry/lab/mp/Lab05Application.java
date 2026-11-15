package com.harry.lab.mp;

import java.util.List;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 走一遍增删改查，验证 BaseMapper 与条件构造器的实际行为。
 *
 * <p>第一，插入后 id 被回填了，说明 IdType.AUTO 生效。
 *
 * <p>第二，selectCount 不带任何条件转换，返回的是纯数字，空结果返回 0。
 *
 * <p>第三，byStatuses 命中两条 RUNNING，且按 id 升序，说明 in 与 orderByAsc 都拼进了 SQL。
 *
 * <p>第四，selectByIds 按传入的 id 顺序之外的主键顺序返回，所以这里先排序再打印。
 *
 * <p>第五，重命名只改名字，其他列不动。生产里这一步传的是整行 DO，
 * 由 FieldStrategy 决定哪些列参与 UPDATE，见 06 号模块。
 */
@SpringBootApplication
public class Lab05Application implements CommandLineRunner {

    private final TaskRepository tasks;

    public Lab05Application(TaskRepository tasks) {
        this.tasks = tasks;
    }

    @Override
    public void run(String... args) {
        tasks.save(newTask("镜像同步", "RUNNING"));
        tasks.save(newTask("镜像清理", "RUNNING"));
        tasks.save(newTask("日志归档", "IDLE"));

        System.out.println("RUNNING 条数 = " + tasks.countRunning());

        var running = tasks.byStatuses(List.of("RUNNING", "IDLE"));
        System.out.println("两种状态共 " + running.size() + " 条，按 id 升序：");
        running.forEach(t -> System.out.println("  " + t.getId() + " / " + t.getTaskName()));

        var picked = tasks.byIds(List.of(1L, 3L));
        System.out.println("按 id 批量查回 " + picked.size() + " 条；空集合直接返回 "
                + tasks.byIds(List.of()).size() + " 条");

        System.out.println("重命名影响行数 = " + tasks.rename(running.get(0).getId(), "镜像同步-已改名"));
    }

    private TaskDO newTask(String name, String status) {
        var task = new TaskDO();
        task.setTaskUuid("uuid-" + name);
        task.setTaskName(name);
        task.setTaskStatus(status);
        return task;
    }

    public static void main(String[] args) {
        SpringApplication.run(Lab05Application.class, args);
    }
}
