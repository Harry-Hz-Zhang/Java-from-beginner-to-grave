package com.harry.lab.sql;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 演示一：什么时候必须手写 SQL，以及手写时的五种写法。
 *
 * <p>【第一类，加锁读】需要 FOR UPDATE 这样的语法，BaseMapper 没有对应方法。
 *
 * <p>【第二类，一次查询带可选筛选条件加手写分页】用条件构造器写会很长，而且 offset
 * 要自己算，见 page 方法。
 *
 * <p>【第三类，状态流转与删除】一条 UPDATE 里要同时改标记、时间戳并校验版本，
 * 用 updateById 会拆成两条语句而失去原子性。
 *
 * <p>【第四类，需要 CASE WHEN、COALESCE(:x, col) 或多表 DELETE JOIN】生产直接换用
 * JdbcClient，不再走 MyBatis，见 RunnerImagePullRepoImpl.java 第 88 到 137 行。
 * 这一类之所以离开 MyBatis，是因为 SQL 已经长到注解里放不下，而项目又坚持不用 XML。
 *
 * <p>【写法一：纯注解 @Select】适合一条固定 SQL。参数用 #{名字} 占位，会被编译成
 * JDBC 的 ? 预编译参数，因此天然防注入。不要用 ${名字}，那是字符串直接拼接。
 *
 * <p>【写法二：script 动态 SQL】注解里用 script 标签包一层，MyBatis 就当成 XML 片段解析。
 * if test 里写的是 OGNL 表达式，判空要写 != null，不能写 status。
 *
 * <p>【写法三：@Update 手写更新】softDelete 就是，一条语句同时置删除标记、版本加一、
 * 记录操作密钥、更新时间。生产 TaskMapper.java 第 31 到 33 行与之逐列一致。
 *
 * <p>【写法四：@Insert 加 foreach 批量】整仓只有一条 @Insert，也只用了一处 foreach
 * 写插入，就是 MessageLogMapper.java 第 16 到 40 行。显式写 jdbcType 是为了 null 参数
 * 也能绑定，ON DUPLICATE KEY UPDATE id = id 让重试的批次跳过已提交的行。
 *
 * <p>【写法五：@Param 包 POJO 的整行更新】一个参数就能带走整行，SQL 里用 #{row.xxx}
 * 取字段，生产 RunnerImageMapper.java 第 31 到 38 行就是这么写的。
 */
public interface TaskMapper extends BaseMapper<TaskDO> {

    /** 加锁读。锁在事务提交时释放，所以必须由 @Transactional 方法调用，见 Lab07Service。 */
    @Select("SELECT * FROM svc_task WHERE task_uuid = #{taskUuid} AND deleted = 0 FOR UPDATE")
    TaskDO lockByUuid(@Param("taskUuid") String taskUuid);

    /**
     * 租户条件写在最前面，每个分页查询都要带，生产 TaskMapper.java 第 23 行同样如此。
     * 名字匹配用 LIKE #{pattern} ESCAPE '!'，pattern 由 SqlSearch.contains 生成。
     */
    @Select("""
            <script>
            SELECT * FROM svc_task
            WHERE org_uuid = #{orgUuid} AND project_uuid = #{projectUuid} AND deleted = 0
            <if test="status != null"> AND task_status = #{status}</if>
            <if test="pattern != null"> AND task_name LIKE #{pattern} ESCAPE '!'</if>
            ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<TaskDO> page(@Param("orgUuid") String orgUuid, @Param("projectUuid") String projectUuid,
                      @Param("status") String status, @Param("pattern") String pattern,
                      @Param("offset") int offset, @Param("limit") int limit);

    /** 一条语句完成四件事：置删除标记、版本自增、记录操作密钥、刷新时间。 */
    @Update("UPDATE svc_task SET deleted = 1, version = version + 1, last_key_uuid = #{keyUuid}, "
            + "updated_at = UTC_TIMESTAMP(3) WHERE id = #{id} AND version = #{version} AND deleted = 0")
    int softDelete(@Param("id") long id, @Param("version") long version, @Param("keyUuid") String keyUuid);

    /** 批量插入。重复的 task_uuid 由唯一键拦下，ON DUPLICATE KEY UPDATE id = id 让它静默跳过。 */
    @Insert("""
            <script>
            INSERT INTO svc_task (created_at, updated_at, version, deleted, org_uuid, project_uuid,
              task_uuid, task_name, task_status, system_prompt)
            VALUES
            <foreach collection="rows" item="r" separator=",">
              (UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), 0, 0, #{r.orgUuid}, #{r.projectUuid},
               #{r.taskUuid}, #{r.taskName}, #{r.taskStatus}, #{r.systemPrompt,jdbcType=VARCHAR})
            </foreach>
            ON DUPLICATE KEY UPDATE id = id
            </script>
            """)
    int insertBatch(@Param("rows") List<TaskDO> rows);

    /** 整行更新：一个 @Param("row") 就把所有字段带进来，可空字段也能显式写成 NULL。 */
    @Update("""
            UPDATE svc_task SET task_name = #{row.taskName}, system_prompt = #{row.systemPrompt},
            updated_by = #{actor}, updated_at = UTC_TIMESTAMP(3), version = version + 1
            WHERE id = #{row.id} AND version = #{row.version} AND deleted = 0
            """)
    int change(@Param("row") TaskDO row, @Param("actor") Long actor);

    /** 投影查询：只取两列，返回 record。列名靠 map-underscore-to-camel-case 映射成属性名。 */
    @Select("SELECT id, task_name FROM svc_task WHERE org_uuid = #{orgUuid} "
            + "AND project_uuid = #{projectUuid} AND deleted = 0 ORDER BY id")
    List<TaskNameRow> names(@Param("orgUuid") String orgUuid, @Param("projectUuid") String projectUuid);

    record TaskNameRow(Long id, String taskName) {
    }
}
