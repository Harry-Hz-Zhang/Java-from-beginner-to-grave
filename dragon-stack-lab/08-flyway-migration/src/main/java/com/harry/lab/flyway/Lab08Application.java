package com.harry.lab.flyway;

import org.flywaydb.core.Flyway;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 启动时 Flyway 会自动执行 db/migration 下所有未执行的脚本，然后这个类才运行。
 *
 * <p>【第一，flyway_schema_history 是什么】Flyway 自己建的表，记录每个脚本的版本号、
 * 描述、校验和、执行耗时、是否成功。每次启动先读这张表，已执行的跳过，没执行的按版本号顺序执行。
 *
 * <p>【第二，为什么用 Flyway 的 Java 接口读而不是写 SQL】历史表的标识符大小写规则
 * 各数据库不同，直接写 SELECT 在 MySQL 上没问题，换到 H2 就可能报列不存在。
 * info() 把方言差异封装掉了，有官方 API 就不要自己拼 SQL。
 *
 * <p>【第三，生产其实不读这张表】harness_saas 的 main 代码里没有任何 Flyway 引用，
 * 只有测试注入 Flyway 调 info()、validate()、repair()。也就是说运行时它完全信任
 * 「启动时自动迁移」这一条路径，状态查询只发生在测试和人工排查时。
 *
 * <p>【第四，为什么这是团队协作的必需品】没有它的时候，新增字段靠人在群里喊
 * 「记得执行一下这个 SQL」，生产上总有人漏掉，然后代码报列不存在。
 *
 * <p>【第五，这个类不再写库】种子数据由 V5 负责，应用只读。这样第二次启动时
 * 不会因为重复插入撞唯一键，也才能看出「Flyway 记录版本、重启不重复执行」这件事。
 */
@SpringBootApplication
public class Lab08Application implements CommandLineRunner {

    private final Flyway flyway;
    private final JdbcTemplate jdbc;

    public Lab08Application(Flyway flyway, JdbcTemplate jdbc) {
        this.flyway = flyway;
        this.jdbc = jdbc;
    }

    @Override
    public void run(String... args) {
        System.out.println("已应用的迁移：");
        for (var migration : flyway.info().applied()) {
            System.out.println("  V" + migration.getVersion() + " / " + migration.getDescription()
                    + " / 状态=" + migration.getState());
        }

        var rows = jdbc.queryForList(
                "SELECT task_uuid, task_name, model, reasoning_effort FROM svc_task ORDER BY id");
        System.out.println("种子数据 " + rows.size() + " 行（V5 只执行一次，重启不会重复插入）：");
        rows.forEach(row -> System.out.println("  " + row.get("task_uuid") + " / " + row.get("task_name")
                + " / " + row.get("model") + " / " + row.get("reasoning_effort")));
    }

    public static void main(String[] args) {
        SpringApplication.run(Lab08Application.class, args);
    }
}
