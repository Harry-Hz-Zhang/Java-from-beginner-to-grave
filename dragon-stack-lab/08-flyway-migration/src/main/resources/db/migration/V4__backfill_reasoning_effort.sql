-- 第四次演进：给已有数据的表加一个非空列，用生产 V11 的三步法。
--
-- 第一步先加成可空列。直接 ADD COLUMN ... NOT NULL 在表里已有数据时会失败，
-- 因为旧行没有值可填。早年教程说「加非空列必须带默认值」，能跑通，但会把默认值
-- 永久写进表定义，之后每次插入都悄悄套用，语义并不清楚。
--
-- 第二步回填。这一步才是真正决定旧行取什么值的地方，可以写业务规则，
-- 比一个静态 DEFAULT 表达能力更强。此刻表里还没有种子数据，所以影响 0 行，
-- 有数据时这条语句就是让下一步能成功的关键。
--
-- 第三步改成 NOT NULL，然后表结构才与「新建时就非空」完全一致。
ALTER TABLE svc_task
    ADD COLUMN reasoning_effort varchar(32) DEFAULT NULL AFTER max_tokens;

UPDATE svc_task SET reasoning_effort = 'OFF' WHERE reasoning_effort IS NULL;

ALTER TABLE svc_task
    MODIFY COLUMN reasoning_effort varchar(32) NOT NULL;
