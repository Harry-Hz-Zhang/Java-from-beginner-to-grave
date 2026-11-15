package com.harry.lab.mp;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * 演示二之一：用包扫描登记 Mapper，而不是在每个接口上写 @Mapper。
 *
 * <p>【第一，这是什么】@Configuration 声明一个配置类，@MapperScan 告诉 MyBatis
 * 去哪个包里找继承了 BaseMapper 的接口，并为它们生成代理对象。
 *
 * <p>【第二，语法怎么写】注解写在类上，括号里是包名，可以写多个包。
 * 扫描是递归的，子包一并覆盖，所以只写 com.harry.lab.mp 就够。
 *
 * <p>【第三，为什么这里需要】去掉 TaskMapper 上的 @Mapper 之后，必须有谁把接口
 * 交给 MyBatis，否则启动时注入 TaskMapper 会报找不到 Bean。包扫描比逐个标注
 * 少写一行、少改一处，新增 Mapper 也不用回头补注解。
 *
 * <p>【第四，生产在哪用】config/PersistenceConfiguration.java 第 16 行，
 * 写法与这里逐字同构，只是包名换成 infrastructure.database.mapper。
 * 生产十四个 Mapper 全部依赖这一行注册。
 */
@Configuration
@MapperScan("com.harry.lab.mp")
public class Lab05Config {
}
