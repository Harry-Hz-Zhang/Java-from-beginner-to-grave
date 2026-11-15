-- Spring Boot 启动时会自动执行这个文件（文件名 schema.sql 是约定的）。
-- 生产项目不用这种方式建表，因为表结构会变、需要版本管理，
-- 那边用的是 Flyway（见 08 号模块）。这里手写建表只是为了少引一个依赖。
CREATE TABLE account (
    name    VARCHAR(16) PRIMARY KEY,
    balance INT         NOT NULL
);

-- 审计流水表。存在的意义是让「两个 Bean 的事务方法共用同一个事务」可以被观察到：
-- transfer 抛异常时，这笔 INSERT 也必须一起回滚。
CREATE TABLE audit_log (
    id      BIGINT AUTO_INCREMENT PRIMARY KEY,
    source  VARCHAR(16) NOT NULL,
    target  VARCHAR(16) NOT NULL,
    amount  INT         NOT NULL
);
