package com.harry.lab.audit;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.apache.ibatis.reflection.MetaObject;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 演示二：让审计字段真正动起来的两块拼图。这个类和 PersistenceConfiguration 一一对应，
 * 连 @MapperScan 都放在同一个类上，与生产第 16 行一致。
 *
 * <p>【第零块：@MapperScan】生产十四个 Mapper 没有任何一个写 @Mapper，
 * 全靠这一行包扫描注册。去掉它，注入 TaskMapper 时会报找不到 Bean。
 *
 * <p>【第一块：乐观锁拦截器】@Version 注解本身只是标记，不注册拦截器它完全不生效，
 * 更新时既不会带上 version 条件也不会自增。凡是插件类能力（乐观锁、分页、防全表更新），
 * 都必须注册到 MybatisPlusInterceptor 里。
 *
 * <p>【MybatisPlusInterceptor 的注册方式】它是唯一入口，其他拦截器通过 addInnerInterceptor
 * 挂上去。多个 innerInterceptor 并存时顺序会影响行为，官方的建议是：多租户与动态表名在前，
 * 分页与乐观锁其次，防全表更新最后。也就是说分页并不放在最后，早年教程里「分页放最后」
 * 的说法与官方顺序相反。生产只注册了乐观锁一个，所以这里不存在顺序问题。
 *
 * <p>【第二块：MetaObjectHandler 填什么】只填四个字段：createdAt、updatedAt、version、deleted。
 * 前两个取 UTC 时间，version 固定 0，deleted 固定 false。
 *
 * <p>【MetaObjectHandler 不填什么】createdBy 与 updatedBy 它一个都不填。原因是拦截器
 * 拿不到请求上下文，不知道自己正替谁写库；这两个值由仓储层从当前操作人取出来再 set，
 * 见生产 UserRepoImpl.java 第 24 到 25 行。这是本模块最容易看漏的一点。
 *
 * <p>【为什么时间要用 UTC】生产全部用 LocalDateTime.now(ZoneOffset.UTC)。
 * 数据库存 UTC 是分布式系统的通行做法：服务器可能在不同时区，存本地时间会导致排序和比较出错。
 */
@Configuration
@MapperScan("com.harry.lab.audit")
public class Lab06Config {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        var interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        return interceptor;
    }

    @Bean
    public MetaObjectHandler auditFill() {
        return new MetaObjectHandler() {
            @Override
            public void insertFill(MetaObject object) {
                var now = LocalDateTime.now(ZoneOffset.UTC);
                strictInsertFill(object, "createdAt", LocalDateTime.class, now);
                strictInsertFill(object, "updatedAt", LocalDateTime.class, now);
                strictInsertFill(object, "version", Long.class, 0L);
                strictInsertFill(object, "deleted", Boolean.class, false);
            }

            @Override
            public void updateFill(MetaObject object) {
                setFieldValByName("updatedAt", LocalDateTime.now(ZoneOffset.UTC), object);
            }
        };
    }
}
