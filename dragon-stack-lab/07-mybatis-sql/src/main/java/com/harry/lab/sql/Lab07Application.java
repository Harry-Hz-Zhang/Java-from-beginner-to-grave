package com.harry.lab.sql;

import java.util.List;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 依次验证手写 SQL 的五条路径：无筛选分页、带筛选分页、租户隔离、锁内更新、批量与整行更新。
 *
 * <p>注意 offset 的算法：第几页减一，乘以每页条数。这个计算必须由调用方保证，
 * 因为项目没有注册分页插件。真实项目里这一步在 Service 层做，limit 的上下界由
 * Controller 的参数校验兜住，见 README 的说明。
 *
 * <p>@MapperScan 与 05、06 号模块同一个理由：生产不在接口上写 @Mapper，
 * 统一由配置类的包扫描注册。
 */
@SpringBootApplication
@MapperScan("com.harry.lab.sql")
public class Lab07Application implements CommandLineRunner {

    private static final String ORG = "org-1";
    private static final String PROJECT = "proj-1";

    private final TaskMapper mapper;
    private final Lab07Service service;

    public Lab07Application(TaskMapper mapper, Lab07Service service) {
        this.mapper = mapper;
        this.service = service;
    }

    @Override
    public void run(String... args) {
        for (int i = 1; i <= 5; i++) {
            var task = row("uuid-" + i, "任务" + i);
            task.setTaskStatus(i % 2 == 0 ? "RUNNING" : "IDLE");
            mapper.insert(task);
        }

        System.out.println("第 1 页（每页 2 条）= " + names(mapper.page(ORG, PROJECT, null, null, 0, 2)));
        System.out.println("第 2 页（每页 2 条）= " + names(mapper.page(ORG, PROJECT, null, null, 2, 2)));
        System.out.println("只看 RUNNING      = " + names(mapper.page(ORG, PROJECT, "RUNNING", null, 0, 10)));
        System.out.println("名字含 3          = " + names(mapper.page(ORG, PROJECT, null, SqlSearch.contains("3"), 0, 10)));
        System.out.println("换个项目就查不到  = " + names(mapper.page(ORG, "proj-2", null, null, 0, 10)).size() + " 条");

        System.out.println("锁内改名影响行数 = " + service.renameUnderLock("uuid-2", "任务2-锁内改名"));

        var batch = List.of(row("uuid-6", "任务6"), row("uuid-7", "任务7"));
        System.out.println("批量插入 = " + mapper.insertBatch(batch) + " 行，重试同一批 = "
                + mapper.insertBatch(batch) + " 行");

        var target = mapper.page(ORG, PROJECT, null, SqlSearch.contains("任务6"), 0, 1).get(0);
        target.setSystemPrompt("新的身份");
        System.out.println("整行更新影响行数 = " + mapper.change(target, 1L));
        // 手写 SQL 不会把新版本号写回对象，本地要自己加一，否则下一次带版本条件的更新会失败。
        target.setVersion(target.getVersion() + 1);

        System.out.println("投影查询 = " + mapper.names(ORG, PROJECT));

        System.out.println("软删除影响行数 = " + mapper.softDelete(target.getId(), target.getVersion(), "key-1"));
        System.out.println("软删除后总数   = " + names(mapper.page(ORG, PROJECT, null, null, 0, 10)).size() + " 条");
    }

    private TaskDO row(String uuid, String name) {
        var task = new TaskDO();
        task.setOrgUuid(ORG);
        task.setProjectUuid(PROJECT);
        task.setTaskUuid(uuid);
        task.setTaskName(name);
        task.setTaskStatus("IDLE");
        return task;
    }

    private List<String> names(List<TaskDO> tasks) {
        return tasks.stream().map(TaskDO::getTaskName).toList();
    }

    public static void main(String[] args) {
        SpringApplication.run(Lab07Application.class, args);
    }
}
