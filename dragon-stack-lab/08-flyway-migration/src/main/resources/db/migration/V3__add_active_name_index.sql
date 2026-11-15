-- 第三次演进：给「未删除的任务名」加唯一约束。
--
-- 生产写法（MySQL 8.0.13 起支持函数唯一索引）：
--   UNIQUE KEY uk_svc_task_active_name ((CASE WHEN deleted = 0 THEN task_name END))
-- 真实用例在 V8__create_runner_image.sql 第 20 到 21 行、V7 第 67 到 68 行、V1 第 74 行。
-- 意思是只对 deleted = 0 的行建唯一键，软删行不占用键位，同名任务删掉之后可以重建。
--
-- H2 不支持表达式索引，所以这里用「生成列 + 普通唯一索引」表达同一件事：
-- active_name 只在未删除时有值，多行 NULL 在唯一索引里互不冲突。
--
-- 另一种生产做法正好相反：唯一键就是不含 deleted 的普通唯一键，软删行继续占着键位，
-- 重新加入时靠 restore() 把那一行复活（V1 第 71 行 + MembershipMapper.java 第 58 到 64 行）。
-- 两种都有人用，区别是「删除后能不能重建同名记录」。
ALTER TABLE svc_task
    ADD COLUMN active_name varchar(128) GENERATED ALWAYS AS (CASE WHEN deleted = 0 THEN task_name END);

CREATE UNIQUE INDEX uk_svc_task_active_name ON svc_task (active_name);
