-- 文件名规则：V 加版本号，加两个下划线，加描述，扩展名 sql。
-- 两个下划线是硬性要求，写成一个下划线 Flyway 不认识这个文件，会直接忽略。
-- 版本号是单段整数或点分数字（V1、V2、V2.1），不要用时间戳，因为执行顺序按版本号排序。
--
-- 这个脚本一旦被执行过，就不能再修改任何一个字符。
-- 原因是 Flyway 会记录文件的校验和，下次启动发现校验和不一致会直接报错并拒绝启动。
-- 需要改结构就新增 V2，而不是回头改 V1。
--
-- 表骨架与生产 db/migration/V1、V8 同构：六个审计列在前，业务列在后。
-- 索引列顺序把 deleted 放在等值列之后、id 之前，是为了让
-- WHERE org_uuid = ? AND project_uuid = ? AND deleted = 0 ORDER BY id
-- 走覆盖索引：等值列先定位，deleted 过滤，id 直接提供顺序，不需要额外排序。
-- 本项目不建物理外键，关联完整性由应用层保证。
CREATE TABLE svc_task (
    id bigint NOT NULL AUTO_INCREMENT,
    created_by bigint DEFAULT NULL,
    updated_by bigint DEFAULT NULL,
    created_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    version bigint NOT NULL DEFAULT 0,
    deleted tinyint(1) NOT NULL DEFAULT 0,
    task_uuid varchar(36) NOT NULL,
    org_uuid varchar(36) NOT NULL,
    project_uuid varchar(36) NOT NULL,
    task_name varchar(128) NOT NULL,
    task_status varchar(16) NOT NULL,
    metadata json DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_svc_task_uuid (task_uuid),
    KEY idx_svc_task_project (org_uuid, project_uuid, deleted, id),
    CONSTRAINT ck_svc_task_status CHECK (task_status IN ('IDLE', 'RUNNING'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;
