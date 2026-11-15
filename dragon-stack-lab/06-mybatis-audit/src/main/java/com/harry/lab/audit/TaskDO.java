package com.harry.lab.audit;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 继承基类之后，这个实体自动获得 id 与六个审计字段，以及它们各自的框架行为。
 *
 * <p>【第一，@EqualsAndHashCode(callSuper = true) 为什么必须写】Lombok 生成 equals 时
 * 默认不包含父类字段。不加这个参数，两个 id 不同但其他字段相同的对象会被判定为相等，
 * 在 Set 或 Map 里会出诡异问题。
 *
 * <p>【第二，NEVER 在这里的用法】生产 TaskDO 给 taskUuid、orgUuid、projectUuid、
 * taskStatus、idleSince、workspaceUri、createdKeyUuid 标了 NEVER，共七处。
 * 注释写着「Derived from execution; only the status statements write it」，
 * 意思是状态只能走专门的 SQL 改，不允许被通用的 updateById 顺手改掉。
 *
 * <p>【第三，ALWAYS 在这里的用法】生产 TaskDO 有十处 ALWAYS，全部落在「允许被清空」的
 * 配置类字段上：image、mcpServers、modelProtocol、modelBaseUrl、modelApiKey、model、
 * reasoningEffort、maxTokens、systemPrompt、metadata。systemPrompt 的注释是
 * 「null keeps the default identity, so an update must be able to clear it」。
 *
 * <p>【第四，对照组】taskName 什么策略都不标，走默认的 NOT_NULL。本模块用它证明
 * 「传 null 的普通字段会被跳过、保留旧值」，与 systemPrompt 的置空效果形成对照。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("svc_task")
public class TaskDO extends BaseModel {

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String taskUuid;

    private String taskName;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String systemPrompt;
}
