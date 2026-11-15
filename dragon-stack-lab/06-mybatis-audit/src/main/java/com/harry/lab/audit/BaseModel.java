package com.harry.lab.audit;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.Version;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 演示一：审计字段基类。这个类和 harness_saas 的 BaseModel 几乎逐行一致。
 *
 * <p>【第一，一共有几个审计字段】六个，不是五个：createdBy、updatedBy、createdAt、
 * updatedAt、version、deleted，再加上主键 id 一共七列。漏掉两个 by 字段是读生产代码时
 * 最常见的偏差，它们在 infrastructure/database/model/BaseModel.java 第 14 到 17 行。
 *
 * <p>【第二，@TableLogic】标记逻辑删除字段。加上之后，框架生成的 delete 不再执行 DELETE，
 * 而是执行 UPDATE ... SET deleted = 1；同时所有 select 和 update 都会自动追加
 * WHERE deleted = 0。代价是表会一直变大，且唯一索引怎么建要单独想清楚，见 08 号模块。
 *
 * <p>【第三，@Version】标记乐观锁字段。它自己不会做任何事，必须配合
 * OptimisticLockerInnerInterceptor 拦截器（见 Lab06Config）。更新时框架会把 version
 * 作为条件拼进 WHERE，并把 version 加一。如果两个请求同时改同一行，后到的那条影响行数为 0。
 *
 * <p>【第四，@TableField(fill = ...)】声明这个字段由 MetaObjectHandler 自动填值。
 * INSERT 表示只在插入时填，INSERT_UPDATE 表示插入和更新都填。
 *
 * <p>【第五，NEVER 与默认策略的分工】createdAt、createdBy 标 NEVER，意味着永不参与 UPDATE，
 * 这是一层代码级的防误改保护。updatedBy 不标任何策略，走默认的 NOT_NULL：调用方传了值
 * 才写进 SET 子句，不传就保持原值。注意注解的默认值是 FieldStrategy.DEFAULT，
 * 不是直接等于 NOT_NULL，DEFAULT 还要再经全局 update-strategy 解析一次。
 */
@Data
public abstract class BaseModel {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long createdBy;

    private Long updatedBy;

    @TableField(fill = FieldFill.INSERT, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @Version
    @TableField(fill = FieldFill.INSERT)
    private Long version;

    @TableLogic
    @TableField(fill = FieldFill.INSERT)
    private Boolean deleted;
}
