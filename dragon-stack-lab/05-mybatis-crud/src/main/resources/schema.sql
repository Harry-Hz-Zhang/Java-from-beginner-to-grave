-- 表名与列名刻意与生产项目保持一致：表 svc_task，列 task_uuid / task_name / task_status。
-- 这样你在 demo 里写的 Lambda 条件，复制到生产代码里连字段名都不用改。
CREATE TABLE svc_task (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_uuid   VARCHAR(64) NOT NULL,
    task_name   VARCHAR(64) NOT NULL,
    task_status VARCHAR(16) NOT NULL
);
