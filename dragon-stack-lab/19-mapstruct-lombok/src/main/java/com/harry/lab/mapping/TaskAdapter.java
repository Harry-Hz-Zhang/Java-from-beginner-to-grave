package com.harry.lab.mapping;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 演示六：持久化字段的映射，用抽象类而不是接口。
 *
 * <p>【第一，unmappedTargetPolicy = ReportingPolicy.ERROR】默认策略是 WARN：
 * 目标对象上有字段没被映射时只打印一行警告，编译照样通过，运行时那个字段是 null。
 * 改成 ERROR 之后，漏映射直接变成编译失败，问题在提交代码之前就暴露。
 * 生产项目的十二个 mapper 里有七个显式写了这个属性，剩下的几个用的也是同样思路的严格配置。
 * 这是本模块最值得带走的一条实践。
 *
 * <p>【第二，为什么是 abstract class】接口不能有实例字段，也就没法注入 Bean。
 * 写成抽象类之后，可以用 @Autowired 注入辅助对象，再在 expression 里调用它。
 * 生产项目十二个 mapper 里有两个是抽象类，正是因为这个需要；
 * 其余十个不需要调用外部对象，写成接口更轻。
 *
 * <p>【第三，expression 里能做什么】它是一段原样嵌入生成代码的 Java 表达式，
 * 所以能调用注入对象的方法、能 new 对象、能写三元表达式。代价是编译器不检查这段字符串，
 * 写错名字要到生成代码阶段才发现。因此它只用来处理「必须写一行代码」的字段，
 * 普通改名仍然用 source/target。
 *
 * <p>【第四，反向映射为什么要 ignore 六个字段】toRow 的目标是 TaskDO，
 * 它从 BaseModel 继承了 createdBy、updatedBy、createdAt、updatedAt、version、deleted。
 * 这六个字段由持久化框架在写入时填充，业务对象里根本不该有，所以显式忽略。
 * 忽略之后 error 策略仍然成立：将来给 TaskDO 加业务字段而忘了映射，编译照样会失败。
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public abstract class TaskAdapter {

    @Autowired
    protected TaskText text;

    @Mapping(target = "uuid", source = "taskUuid")
    @Mapping(target = "name", source = "taskName")
    @Mapping(target = "status", source = "taskStatus")
    @Mapping(target = "label", expression = "java(text.label(row.getTaskName(), row.getTaskStatus()))")
    public abstract TaskBO toBo(TaskDO row);

    @Mapping(target = "taskUuid", source = "uuid")
    @Mapping(target = "taskName", source = "name")
    @Mapping(target = "taskStatus", source = "status")
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "version", ignore = true)
    @Mapping(target = "deleted", ignore = true)
    public abstract TaskDO toRow(TaskBO task);
}
