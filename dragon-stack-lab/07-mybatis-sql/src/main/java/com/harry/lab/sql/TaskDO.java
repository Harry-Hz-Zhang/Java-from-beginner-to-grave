package com.harry.lab.sql;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 这个模块只用最朴素的实体，把注意力全部留给 SQL 写法本身。
 *
 * <p>第一，字段只保留手写 SQL 会用到的列：租户三件套 orgUuid、projectUuid、taskUuid，
 * 业务列 taskName、taskStatus，可清空的 systemPrompt，以及乐观锁与审计用的 version、updatedBy。
 *
 * <p>第二，这里没有继承 BaseModel，也没有 @Version 与 @TableLogic。手写 SQL 时
 * 这些注解不参与生成语句，版本条件、删除标记都要自己在 SQL 里写出来，这正是本模块的重点。
 *
 * <p>第三，真实项目的实体有二十多个字段，但映射规则和这里完全一样：
 * 驼峰转下划线，主键交给数据库自增。
 */
@Data
@TableName("svc_task")
public class TaskDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String orgUuid;

    private String projectUuid;

    private String taskUuid;

    private String taskName;

    private String taskStatus;

    private String systemPrompt;

    private Long updatedBy;

    private Long version;
}
