package com.harry.lab.mapping;

import java.time.LocalDateTime;

/**
 * 演示三：两跳映射的第一跳，从数据库行对象变成领域对象。
 *
 * <p>【为什么需要这一层】TaskDO 的字段名跟着数据库走（task_uuid、task_name），
 * 而且带着六个审计字段。业务代码不应该关心这些，所以先转成 BO：
 * 字段名换成业务语言（uuid、name），审计字段留在 DO 里不再往上传。
 *
 * <p>【为什么用 record】BO 一旦构造就不应该被修改，record 天然不可变，
 * 也不会有 setter 被误调。它的访问器叫 uuid() 而不是 getUuid()，
 * MapStruct 对两种命名都能识别，不需要额外配置。
 *
 * <p>【label 是派生字段】它不是数据库里的列，而是由 TaskText 这个辅助 Bean
 * 从名称和状态算出来的展示文案。这种「需要一段代码才能算出来」的字段，
 * 就是 @Mapping(expression = "java(...)") 的用武之地，见 TaskAdapter。
 */
public record TaskBO(Long id, String uuid, String name, String status, String label, LocalDateTime createdAt) {
}
