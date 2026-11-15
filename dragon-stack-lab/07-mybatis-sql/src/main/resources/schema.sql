-- 手写 SQL 不会走 MetaObjectHandler，所以 version、updated_at、deleted 三列必须由
-- 数据库提供默认值，否则插入时不带这三列会直接撞 NOT NULL 约束。
-- 这是一个很容易忽略的细节：自动填充只在框架生成的 SQL 上生效。
--
-- H2 没有 MySQL 的 UTC_TIMESTAMP，这里用同名别名补齐，好处是本模块的 Java 代码里
-- 写的 SQL 与生产逐字一致，复制过去不用改。
CREATE ALIAS IF NOT EXISTS UTC_TIMESTAMP AS 'java.sql.Timestamp utc(int digits) {
    return java.sql.Timestamp.from(java.time.Instant.now());
}';

CREATE TABLE svc_task (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    org_uuid      VARCHAR(36)  NOT NULL,
    project_uuid  VARCHAR(36)  NOT NULL,
    task_uuid     VARCHAR(36)  NOT NULL,
    task_name     VARCHAR(64)  NOT NULL,
    task_status   VARCHAR(16)  NOT NULL,
    system_prompt VARCHAR(255) DEFAULT NULL,
    updated_by    BIGINT       DEFAULT NULL,
    version       BIGINT       NOT NULL DEFAULT 0,
    last_key_uuid VARCHAR(36)  NOT NULL DEFAULT '',
    created_at    TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    deleted       TINYINT      NOT NULL DEFAULT 0,
    UNIQUE KEY uk_svc_task_uuid (task_uuid)
);
