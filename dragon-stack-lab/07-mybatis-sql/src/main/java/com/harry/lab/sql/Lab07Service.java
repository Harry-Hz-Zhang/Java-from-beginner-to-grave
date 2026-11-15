package com.harry.lab.sql;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 演示二：把 FOR UPDATE 真正放进事务里调用。
 *
 * <p>【第一，为什么必须单独一个类】Spring 的 @Transactional 靠代理实现，同类内部
 * 自调用不经过代理，注解不生效。放在单独的 Bean 里，外部调用才会开启事务。
 *
 * <p>【第二，事务边界在哪】方法进入时开启，方法正常返回时提交。行锁在提交时释放，
 * 所以「读出来、判断、改回去」这三步必须都在方法内完成，锁才有意义。
 *
 * <p>【第三，为什么不用 CommandLineRunner 直接调】CommandLineRunner 上没有事务，
 * 每条语句自动提交，FOR UPDATE 加锁后立刻释放，等于没加锁。这是本模块最容易做错的一步。
 *
 * <p>【第四，生产的对应位置】TaskRepoImpl.java 第 43 到 45 行的 lockByUuid 与
 * 第 52 到 56 行的 update，中间夹着状态判断，全部在应用服务的同一个事务里。
 */
@Service
public class Lab07Service {

    private final TaskMapper mapper;

    public Lab07Service(TaskMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public int renameUnderLock(String taskUuid, String newName) {
        var locked = mapper.lockByUuid(taskUuid);
        if (locked == null) {
            return 0;
        }
        var row = new TaskDO();
        row.setId(locked.getId());
        row.setVersion(locked.getVersion());
        row.setTaskName(newName);
        return mapper.updateById(row);
    }
}
