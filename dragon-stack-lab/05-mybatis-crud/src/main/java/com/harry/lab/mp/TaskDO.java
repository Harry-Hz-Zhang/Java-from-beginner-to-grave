package com.harry.lab.mp;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 演示一：实体类与数据库表的映射规则。
 *
 * <p>【@TableName 的作用】告诉 MyBatis-Plus 这个类对应哪张表。不写时框架按类名
 * 转下划线去猜（TaskDO 会猜成 task_d_o），基本一定猜错，所以生产代码全部显式声明。
 * harness_saas 的实体统一叫 XxxDO，表统一加 svc_ 前缀，例如 svc_task。
 *
 * <p>【@TableId 的作用】标记主键字段。type = IdType.AUTO 表示交给数据库自增，
 * 插入时不用给主键，框架会把自增结果回填到这个对象里。
 * 其他常用取值：ASSIGN_ID 由框架生成雪花 id，INPUT 由你手动赋值。
 *
 * <p>【字段名到列名的转换】框架靠「驼峰转下划线」的规则把 taskUuid 映射到 task_uuid。
 * 这条规则的开关是 application.yaml 里的 mybatis-plus.configuration.map-underscore-to-camel-case，
 * 默认就是 true。如果你发现查出来某个字段永远是 null，先来这里对一下名字。
 *
 * <p>【@Data 在这里生成了什么】它生成 getter、setter、toString、equals 和 hashCode。
 * 这三样对 MyBatis-Plus 是硬性要求：框架查询时先 new 一个空对象，
 * 再逐个调用 setter 把列值写进去；插入时则调用 getter 取值。
 * 所以你如果只写 getter 不写 setter，查出来会全是 null 而且不报错。
 * Lombok 的工作原理（注解处理器、与 MapStruct 的顺序问题）在 19 号模块展开。
 */
@Data
@TableName("svc_task")
public class TaskDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String taskUuid;

    private String taskName;

    private String taskStatus;
}
