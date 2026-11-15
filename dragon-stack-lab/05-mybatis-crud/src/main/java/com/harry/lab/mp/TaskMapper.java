package com.harry.lab.mp;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * 演示二：继承 BaseMapper 之后，单表增删改查一行 SQL 都不用写。
 *
 * <p>【第一，BaseMapper 到底给多少个方法】3.5.18 里是二十一个方法名：
 * insert、deleteById、deleteByMap、delete、deleteBatchIds、deleteByIds、updateById、update、
 * selectById、selectByIds、selectBatchIds、selectByMap、selectOne、exists、selectCount、
 * selectList、selectMaps、selectObjs、selectPage、selectMapsPage、insertOrUpdate。
 * 其中 deleteBatchIds 与 selectBatchIds 已经标了 @Deprecated，替代者是 deleteByIds 与 selectByIds。
 * 早年的教程说十七个，那是 3.4 之前的数字，看到时要在心里换算一下。
 * 还要注意 selectPage 与 selectMapsPage 依赖 PaginationInnerInterceptor，本项目没有注册
 * 分页插件，所以这两个方法即使调用也不会真正分页，生产因此一次都没用过它们。
 *
 * <p>【第二，为什么接口不用实现类】这些方法在启动时由 MyBatis-Plus 动态生成实现。
 * 它扫描到继承了 BaseMapper 的接口，就为每个方法生成对应的 MappedStatement
 * （也就是一条预编译的 SQL 模板），并注册一个代理对象到容器里。
 * 这就是为什么你注入 TaskMapper 能直接用，却在整个项目里搜不到任何实现类。
 *
 * <p>【第三，@Mapper 与 @MapperScan 怎么选】@Mapper 标在每个接口上，精确但啰嗦；
 * @MapperScan("包名") 标在配置类上，一次扫描整个包。这个模块选后者，见 Lab05Config。
 * 生产项目也是后者：config/PersistenceConfiguration.java 第 16 行的 @MapperScan
 * 扫 infrastructure/database/mapper 包，十四个 Mapper 接口上一个 @Mapper 都没有。
 * 两个都写不会报错，但没必要。
 *
 * <p>【第四，「空接口走天下」是一种误解】生产十四个 Mapper 里，十三个都声明了
 * 二到十一个注解方法，只有 RoleMapper 是真正的空接口。BaseMapper 覆盖的是单表
 * 增删改查，一旦涉及多条件分页、加锁读、批量写入，就必须自己写方法，见 07 号模块。
 */
public interface TaskMapper extends BaseMapper<TaskDO> {
}
