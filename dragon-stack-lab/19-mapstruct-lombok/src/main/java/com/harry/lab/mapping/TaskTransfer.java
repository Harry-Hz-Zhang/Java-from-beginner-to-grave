package com.harry.lab.mapping;

import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

/**
 * 演示七：两跳映射的第二跳，BO 到对外视图。
 *
 * <p>【第一，这里一个 @Mapping 都不需要】BO 的字段名已经是 uuid、name、status、label，
 * VO 用的是同一套名字，MapStruct 按名字自动对接。id 不在 VO 上，
 * 属于「源有、目标没有」，不违反 error 策略（它只管目标有没有被填满）。
 * 这一条正好说明：字段改名应该发生在持久化边界，而不是视图边界。
 *
 * <p>【第二，为什么它可以是接口】不需要注入任何 Bean，没有状态，
 * 接口就够了，生成的实现类更小。生产项目的五个 *Transfer 全部是接口。
 *
 * <p>【第三，两个 mapper 的分工】TaskAdapter 知道数据库列名，TaskTransfer 知道接口契约，
 * 两者互不知道对方的存在。将来数据库列改名只动前者，接口字段改名只动后者。
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface TaskTransfer {

    TaskVO toVo(TaskBO task);
}
