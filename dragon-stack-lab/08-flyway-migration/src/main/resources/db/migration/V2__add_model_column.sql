-- 第二次演进：给任务表加三个模型配置列。
-- 注意这里写的是 ALTER TABLE 而不是回头去改 V1。
--
-- 生产 V11 把这些列合并成一条 ALTER 语句，一次重建表；H2 不接受逗号分隔的多列 ADD，
-- 所以这里拆成三条等价语句。每一条的 AFTER 与生产一致：新增列永远指定物理位置，
-- 这样不同环境 dump 出来的表结构顺序相同，比对时不会出现「列都在、顺序不同」的假差异。
ALTER TABLE svc_task
    ADD COLUMN model_protocol varchar(32) DEFAULT NULL AFTER task_name;

ALTER TABLE svc_task
    ADD COLUMN model varchar(128) DEFAULT NULL AFTER model_protocol;

ALTER TABLE svc_task
    ADD COLUMN max_tokens int DEFAULT NULL AFTER model;
