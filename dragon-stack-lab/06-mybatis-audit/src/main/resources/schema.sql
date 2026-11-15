-- 审计六个字段的列定义：created_by、updated_by、created_at、updated_at、version、deleted。
-- version 用 BIGINT 配合 @Version，deleted 用 TINYINT 配合 @TableLogic。
-- 注意 deleted 是 Boolean 类型，H2 里 TINYINT 与 Boolean 可以互通，MySQL 里同样常见。
--
-- 最后一行补一个 UTC_TIMESTAMP 函数。生产的 softDelete 写的是 UTC_TIMESTAMP(3)，
-- 而 H2 没有这个函数，用同名别名补齐之后，下面的 Java 代码里的 SQL 能与生产逐字一致。
CREATE ALIAS IF NOT EXISTS UTC_TIMESTAMP AS 'java.sql.Timestamp utc(int digits) {
    return java.sql.Timestamp.from(java.time.Instant.now());
}';

CREATE TABLE svc_task (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    created_by    BIGINT      DEFAULT NULL,
    updated_by    BIGINT      DEFAULT NULL,
    created_at    TIMESTAMP   NOT NULL,
    updated_at    TIMESTAMP   NOT NULL,
    version       BIGINT      NOT NULL,
    deleted       TINYINT     NOT NULL,
    task_uuid     VARCHAR(64) NOT NULL,
    task_name     VARCHAR(64) NOT NULL,
    system_prompt VARCHAR(255) DEFAULT NULL,
    last_key_uuid VARCHAR(64) NOT NULL DEFAULT ''
);
