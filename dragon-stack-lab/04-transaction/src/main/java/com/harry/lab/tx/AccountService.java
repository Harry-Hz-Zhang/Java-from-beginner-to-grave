package com.harry.lab.tx;

import java.io.IOException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;

/**
 * 演示一：@Transactional 做了什么、它在什么情况下失效，以及编程式事务怎么用。
 *
 * <p>【它不是魔法开关，而是一层代理】Spring 启动时给这个类生成代理，调用方拿到的是代理。
 * 第一，代理在真实方法前后插入 begin、commit、rollback。第二，只有通过代理调用才生效，
 * 方法必须是 public，因为代理只能重写 public 方法。第三，同类内部 this 调用绕过代理，
 * 事务注解被完全忽略，这就是 transferWrong 演示的场景。
 *
 * <p>【生产的事务参数】第一，生产 29 处 @Transactional 里 28 处显式写 isolation，
 * 全部是 READ_COMMITTED，剩下 1 处是只读查询。第二，17 处带 timeout = 30，
 * 防止一次写事务把连接占住不放。第三，rollbackFor 一处也没有，因为生产所有业务异常
 * 都继承 RuntimeException，默认规则就够。第四，propagation 一处也没有，全部用默认的 REQUIRED。
 *
 * <p>【默认只对运行时异常回滚】rollbackFor 的默认值是 RuntimeException 及其子类。
 * 抛受检异常（例如 IOException）时事务照常提交，数据已经改了，这是线上事故的常见来源。
 * transferChecked 演示「不写就提交」，transferCheckedRollback 演示显式写 rollbackFor 的效果。
 *
 * <p>【编程式事务】第一，长 IO 必须关在事务外：事务里等网络或磁盘，会把数据库连接和锁
 * 一起拖住。第二，只把短写操作放进 TransactionTemplate，参数用 setIsolationLevel 与
 * setTimeout 显式指定。第三，生产 application/TaskApp.java:38,52-54,154-166 就是这个做法：
 * 注入 PlatformTransactionManager 构造 TransactionTemplate，长流程在模板外，短写操作在
 * execute 里。第四，status.setRollbackOnly() 用来在没有异常时主动回滚一个已标记的失败。
 *
 * <p>【事务提交之后再起异步】直接在事务里提交异步任务，异步线程可能读到未提交的数据。
 * 正确做法是注册 TransactionSynchronization，在 afterCompletion 里判断
 * STATUS_COMMITTED 再投递。生产 application/TaskRunApp.java:51-60 就是这样做的。
 */
@Service
public class AccountService {

    private final JdbcTemplate jdbc; private final AuditService audit; private final TransactionTemplate template;

    /** 构造编程式事务模板：隔离级别与超时和声明式方法保持一致，避免两套参数行为不同。 */
    public AccountService(JdbcTemplate jdbc, AuditService audit, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc; this.audit = audit; this.template = new TransactionTemplate(transactionManager);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED); template.setTimeout(30);
    }

    /** 正常路径：通过代理调用，两笔更新与审计记录在同一个事务里，异常时一起回滚。 */
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 30)
    public void transfer(String from, String to, int amount) {
        move(from, to, amount); audit.record(from, to, amount);
        if (amount > 100) throw new IllegalStateException("单笔限额 100，事务应当回滚");
    }

    /** 错误示范：同类内部自调用，事务代理被绕过，两笔更新都会真实落库。 */
    public void transferWrong(String from, String to, int amount) { this.transfer(from, to, amount); }

    /** 对照一：抛受检异常但不写 rollbackFor，事务照常提交，数据已经改了。 */
    public void transferChecked(String from, String to, int amount) throws IOException {
        move(from, to, amount);
        if (amount > 100) throw new IOException("受检异常，默认不回滚");
    }

    /** 对照二：显式写 rollbackFor = Exception.class，同样的受检异常会触发回滚。 */
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 30, rollbackFor = Exception.class)
    public void transferCheckedRollback(String from, String to, int amount) throws IOException {
        move(from, to, amount);
        if (amount > 100) throw new IOException("受检异常，显式要求回滚");
    }

    /** 编程式事务：先做长 IO，再进模板；金额超限时不抛异常，用 setRollbackOnly 主动回滚。 */
    public void transferProgrammatic(String from, String to, int amount) {
        try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        template.execute(status -> {
            move(from, to, amount);
            if (amount > 100) status.setRollbackOnly();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int state) {
                    if (state == TransactionSynchronization.STATUS_COMMITTED) System.out.println("[事务后] 已提交，可以安全投递异步任务");
                }
            });
            return null;
        });
    }

    /** 只读事务：向数据库声明本次不开写事务，也是一份「这里不该有写操作」的契约。 */
    @Transactional(readOnly = true)
    public int balance(String name) { return jdbc.queryForObject("SELECT balance FROM account WHERE name = ?", Integer.class, name); }

    /** 两笔更新放在一个私有方法里，四个演示方法复用，避免重复 SQL 顶高行数。 */
    private void move(String from, String to, int amount) {
        jdbc.update("UPDATE account SET balance = balance - ? WHERE name = ?", amount, from);
        jdbc.update("UPDATE account SET balance = balance + ? WHERE name = ?", amount, to);
    }
}
