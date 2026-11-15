package com.harry.lab.mapping;

import java.time.LocalDateTime;

/**
 * 演示四：两跳映射的第二跳，从领域对象变成对外视图。
 *
 * <p>【为什么还要再分一层，而不是 BO 直接返回】三个理由。
 * 第一，BO 带着主键 id，那是数据库内部标识，对外暴露会让调用方按自增 id 猜数据量，
 * 生产项目的接口测试里就有一条断言专门检查响应里没有 id 字段。
 * 第二，对外的字段集合会随产品需求变化，而 BO 的字段集合随领域模型变化，
 * 两者变化的原因不同，混在一起会让每次改接口都牵动领域层。
 * 第三，视图层将来要加脱敏、格式化、多语言，这些都不该污染 BO。
 *
 * <p>【两跳的代价】多一个类、多一次对象创建。对于这个规模的系统，这个代价可以忽略，
 * 换来的是两层各自只对自己关心的字段负责：TaskAdapter 只碰持久化字段，
 * TaskTransfer 只碰对外字段。生产项目里前者叫 *Adapter（七个，在 repository 层），
 * 后者叫 *Transfer（五个，在 service 层）。
 */
public record TaskVO(String uuid, String name, String status, String label, LocalDateTime createdAt) {
}
