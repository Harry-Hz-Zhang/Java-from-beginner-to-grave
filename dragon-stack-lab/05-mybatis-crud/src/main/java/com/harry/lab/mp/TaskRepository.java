package com.harry.lab.mp;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.Collection;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * 演示三：用 LambdaQueryWrapper 拼条件，以及 selectCount、selectByIds 的返回约定。
 *
 * <p>【第一，为什么用 Lambda 形式而不是字符串形式】字符串形式叫 QueryWrapper，
 * 写法是 .eq("task_status", "RUNNING")。字段名写成字符串就没有编译期检查，
 * 你把 task_status 打成 task_stauts，编译照过，运行时才报 SQL 错误。
 * Lambda 形式写 TaskDO::getTaskStatus，字段改名时 IDE 会帮你一起改，
 * 这是生产项目统一使用 Wrappers.lambdaQuery() 的原因。
 *
 * <p>【第二，常用条件方法】eq 等于、ne 不等于、gt/ge/lt/le 比较、in/notIn 集合、
 * isNull/isNotNull 空值、between 区间、orderByAsc/orderByDesc 排序。
 * 每个方法都有一个带 boolean 条件的重载，例如 .eq(flag, TaskDO::getStatus, "RUNNING")，
 * 当 flag 为 false 时这个条件不会拼进 SQL，可以替代 if 判断。
 *
 * <p>【第三，为什么不用 likeRight 这类模糊方法】生产代码里零处使用 likeRight。
 * 原因是一旦关键字来自用户输入，就必须自己转义 % 和 _，否则用户输入一个百分号
 * 就等于把全表扫一遍。生产的做法是统一写成 SQL 里的 LIKE #{pattern} ESCAPE '!'，
 * 再用 SqlSearch.contains 转义，见 07 号模块。
 *
 * <p>【第四，countRunning 为什么可以返回 long】selectCount 的签名返回 Long，
 * 空结果集返回 0 而不是 null，所以直接 return 并自动拆箱是安全的。
 * 生产 infrastructure/repository/TaskRunRepoImpl.java 第 46 到 52 行就是这种写法。
 *
 * <p>【第五，批量按 id 查为什么用 selectByIds】selectBatchIds 自 3.5.7 起已废弃，
 * 新名字是 selectByIds。它内部会拼 IN 条件，传空集合会生成非法 SQL，
 * 所以调用方要先判空。生产 UserRepoImpl.java 第 76 到 80 行就是先判空再查。
 */
@Repository
public class TaskRepository {

    private final TaskMapper mapper;

    public TaskRepository(TaskMapper mapper) {
        this.mapper = mapper;
    }

    public Long save(TaskDO task) {
        mapper.insert(task);
        return task.getId();
    }

    public long countRunning() {
        return mapper.selectCount(Wrappers.<TaskDO>lambdaQuery()
                .eq(TaskDO::getTaskStatus, "RUNNING"));
    }

    public List<TaskDO> byIds(Collection<Long> ids) {
        return ids.isEmpty() ? List.of() : mapper.selectByIds(ids);
    }

    /**
     * 一次演示 in、isNotNull、orderByAsc 三个生产在用的条件方法。
     * 生产 ProjectMemberRepoImpl.java 第 74 到 77 行用 in 查多个项目的角色，
     * RuntimeRepoImpl.java 第 30 到 32 行用 isNotNull 加 orderByAsc 查活跃运行时。
     */
    public List<TaskDO> byStatuses(List<String> statuses) {
        return mapper.selectList(Wrappers.<TaskDO>lambdaQuery()
                .in(TaskDO::getTaskStatus, statuses)
                .isNotNull(TaskDO::getTaskName)
                .orderByAsc(TaskDO::getId));
    }

    /**
     * 注意这个方法只设 id 和名字，是教学上的简化写法。
     * 生产不是这样：TaskAdapter.taskRow() 会把业务对象映射成完整的一行 DO，
     * 哪些列真的写进 UPDATE 由字段上的 FieldStrategy 决定，不是由调用方决定。
     */
    public int rename(Long id, String newName) {
        var patch = new TaskDO();
        patch.setId(id);
        patch.setTaskName(newName);
        return mapper.updateById(patch);
    }
}
