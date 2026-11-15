package com.harry.lab.mapping;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 演示二：Lombok 与继承同时出现时的两个要点。
 *
 * <p>【第一，继承之后必须写 callSuper】@Data 生成的 equals 只比较本类的字段，
 * 父类的六个字段会被忽略。两个 createdBy 不同的对象会被判定为相等，
 * 这在集合去重、缓存 key 的场景里会出真问题。加上 callSuper = true 之后，
 * 生成的 equals 会先调用父类的 equals，把父类字段一起比进来。
 * 生产项目里所有继承 BaseModel 的实体都写了这个属性，共十五处。
 *
 * <p>【第二，父类字段的存在会影响映射】MapStruct 是按「所有可读可写属性」匹配的，
 * 父类的六个审计字段同样算目标属性。从 BO 反向生成 DO 时，
 * 这些字段没有对应的来源，如果开着严格检查就会在编译期报错，
 * 所以要显式写 @Mapping(target = ..., ignore = true)。这是使用父类换来的代价，
 * 也是本模块演示的重点之一。
 *
 * <p>【字段名刻意与对外字段不同】taskUuid、taskName、taskStatus 对应数据库列，
 * 对外的名字是 uuid、name、status。名字不一致就必须写 @Mapping，
 * 关于「不写会怎样」的准确结论见 README 的第五条。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class TaskDO extends BaseModel {

    private Long id;

    private String taskUuid;

    private String taskName;

    private String taskStatus;
}
