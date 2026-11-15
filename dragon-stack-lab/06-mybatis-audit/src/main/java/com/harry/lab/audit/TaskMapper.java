package com.harry.lab.audit;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 逻辑删除与乐观锁都是拦截器层面的行为，不体现在方法签名上，所以这里只有一个手写方法。
 *
 * <p>【为什么不用 deleteById 演示逻辑删除】生产十四个 Mapper 里 deleteById 的调用点
 * 是零。所有删除都走「带 version 条件、带操作密钥、带时间戳」的手写 UPDATE，
 * 这样删除本身也受乐观锁保护，并且能留下是谁在什么上下文里删的痕迹。
 * 这个模块照生产的写法补一条 softDelete，顺便验证 @TableLogic 的过滤效果。
 *
 * <p>【参数为什么都写 @Param】三个参数，不写就无法保证名字解析稳定。
 * 生产同样写了 @Param，见 TaskMapper.java 第 31 到 33 行。
 */
public interface TaskMapper extends BaseMapper<TaskDO> {

    @Update("UPDATE svc_task SET deleted = 1, version = version + 1, last_key_uuid = #{keyUuid}, "
            + "updated_at = UTC_TIMESTAMP(3) WHERE id = #{id} AND version = #{version} AND deleted = 0")
    int softDelete(@Param("id") long id, @Param("version") long version, @Param("keyUuid") String keyUuid);
}
