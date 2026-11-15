package com.harry.lab.mapping;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 演示一：所有实体的公共父类，六个审计字段集中放在这里。
 *
 * <p>【为什么把它们抽成父类】这六个字段（创建人、更新人、创建时间、更新时间、版本号、
 * 逻辑删除）在每一张业务表上都有，而且都不是业务属性。抽到父类之后，
 * 每个实体类只剩自己真正关心的字段，映射配置也只需要在一个地方处理。
 * 生产项目的 BaseModel 就是这种写法，并且额外用 MyBatis-Plus 的注解
 * 声明了自动填充策略。
 *
 * <p>【本 demo 只保留字段本身】为了让依赖降到最少，这里没有引入 MyBatis-Plus 的注解，
 * 只留下字段和一个 @Data。教学重点是「子类怎么处理父类字段的映射」，
 * 而不是自动填充的配置细节。
 *
 * <p>【为什么写成 abstract】它只用来被继承，不应该被直接实例化。
 * 刻意不写 @Builder，因为父类带构造器的 Builder 会让子类的 Builder 继承变得别扭，
 * 生产项目也没有这么做。
 */
@Data
public abstract class BaseModel {

    private Long createdBy;

    private Long updatedBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Long version;

    private Boolean deleted;
}
