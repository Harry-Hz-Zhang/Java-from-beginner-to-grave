package com.harry.lab.tx;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/**
 * 演示七：把事务方法拆到另一个 Bean，也是「自调用失效」的正解之一。
 *
 * <p>【这是什么】一个只负责写审计流水的小服务，方法上标了 @Transactional。
 *
 * <p>【为什么需要它】第一，AccountService 的 transfer 调用它时，调用方已经是代理，
 * 所以这里的事务注解会正常生效。第二，@Transactional 默认传播行为是 REQUIRED：
 * 外层已有事务就加入，没有才新建。因此 transfer 与 record 共用同一个事务，
 * transfer 后面的异常会让审计记录一起回滚。第三，日志与业务数据同生共死，
 * 正是「拆到另一个 Bean」比注入自身代理更清晰的地方：职责也顺带分开了。
 *
 * <p>【生产对照】生产 29 处 @Transactional 分布在 8 个类，全部靠跨 Bean 调用生效，
 * 没有一处在同类内部自调用。
 */
@Service public class AuditService {

    private final JdbcTemplate jdbc;

    public AuditService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 写一条审计流水。isolation 与 timeout 与业务方法保持一致，避免内外层参数打架。 */
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 30)
    public void record(String from, String to, int amount) {
        jdbc.update("INSERT INTO audit_log(source, target, amount) VALUES (?, ?, ?)", from, to, amount);
    }
}
