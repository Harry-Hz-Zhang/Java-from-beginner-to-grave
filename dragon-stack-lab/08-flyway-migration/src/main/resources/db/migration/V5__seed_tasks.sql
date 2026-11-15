-- 第五次演进：纯 DML 的种子数据。生产 V2 灌固定角色、V3 灌唯一超级管理员，都是这个形态。
--
-- 一条 INSERT 写多行，比五条单行 INSERT 少四次往返，也让「这批数据要么全进、要么全不进」
-- 变成一个原子操作。
--
-- Flyway 记录的是版本号而不是内容：这条脚本执行过一次之后，重启时会被跳过，
-- 不会重复插入，也不会撞唯一键。这正是它比「把 seed.sql 放 resources 里手工执行」可靠的地方。
INSERT INTO svc_task (task_uuid, org_uuid, project_uuid, task_name, task_status,
                      model_protocol, model, max_tokens, reasoning_effort)
VALUES ('uuid-1', 'org-1', 'proj-1', '镜像同步', 'IDLE', 'ANTHROPIC_MESSAGES', 'deepseek-chat', 4096, 'OFF'),
       ('uuid-2', 'org-1', 'proj-1', '日志归档', 'RUNNING', 'OPENAI_CHAT_COMPLETIONS', 'deepseek-reasoner', 8192, 'MEDIUM');
