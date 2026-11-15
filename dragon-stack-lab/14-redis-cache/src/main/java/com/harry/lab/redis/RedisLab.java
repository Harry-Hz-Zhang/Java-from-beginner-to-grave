package com.harry.lab.redis;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.stereotype.Component;

/**
 * 演示一：生产里 Redis 的三种真实写法——会话、固定窗口限流、带负缓存的读穿缓存。
 *
 * <p>【这是什么】一个薄封装，三个方法分别对应生产的 RedisSessionStore、RedisRateLimiter、
 * RedisProjectKeyCache。它跑在本机单机 Redis 上，但每一处写法都是为集群和线上故障准备的。
 *
 * <p>【第一，为什么用 StringRedisTemplate】RedisTemplate 默认 JDK 序列化，值在 redis-cli 里
 * 是一堆乱码，排查时完全没法读。StringRedisTemplate 键值都用字符串序列化，存对象自己转 JSON，
 * 比依赖 JDK 序列化可控，也不会因为类结构变化而反序列化失败。
 *
 * <p>【第二，限流为什么写成 Lua】INCR 之后再 PEXPIRE 是两条命令，进程在中间崩掉，
 * 计数器就永远不过期，客户端被永久限流。生产早不是「建议用脚本」而是「就是这么写的」：
 * infrastructure/session/RedisRateLimiter.java 第 18 到 25 行把 INCR 与 PEXPIRE 合成一个
 * DefaultRedisScript，全仓一共九个 Lua 脚本。脚本执行期间不插入别的命令，两步天然原子。
 * 还要说清它是固定窗口不是滑动窗口：第一个 INCR 之后设整个窗口的过期，窗口内一路累加，
 * 到点整体清零，没有滑动平均。
 *
 * <p>【第三，key 里的花括号是什么】这是 Redis Cluster 的 hash tag。集群按 key 的哈希分片，
 * 但只取第一个 { 到 } 之间的内容做哈希，所以 lab:{iam}:session:xxx 与 lab:{iam}:rate:xxx
 * 必然落在同一个槽位。Lua 里一旦动态拼 key，没有 hash tag 就会跨节点取键，直接报 CROSSSLOT。
 * 生产 RedisSessionStore 第 19 行的 iam:{iam}:、RedisProjectKeyCache 第 20 到 22 行的
 * project:{project-auth}: 都是这个原因，后者的注释原文是 One hash tag keeps Lua's dynamically
 * derived organization keys in a single Redis Cluster slot。
 *
 * <p>【第四，为什么用毫秒级 TTL】生产对过期精度要求到十秒、三十秒级，RedisSessionStore 第 82 行、
 * RedisRateLimiter 第 33 行、RedisProjectKeyCache 第 52 行与第 115 行传的都是毫秒。
 * SET 的 EX 只有秒级，不足一秒会被截断；Expiration.milliseconds(...) 生成的是
 * SET key value PX n，与 PSETEX 同精度。
 *
 * <p>【第五，Redis 挂了怎么办】不能让异常冒成 500，那会让调用方以为自己请求写错了。
 * 生产 RedisSessionStore 第 212 到 214 行返回「会话服务暂不可用」，GlobalExceptionHandler
 * 第 51 到 54 行把 DataAccessException 统一转成 503 SERVICE_UNAVAILABLE，提示稍后重试。
 * 这里用 guard 包一层，抛 Unavailable，由调用方按 503 处理。
 *
 * <p>【第六，负缓存解决什么】查一个根本不存在的键，每次都会穿过缓存打到数据库，这叫缓存穿透。
 * 办法是把「查不到」也缓存下来，TTL 给得很短。生产 RedisProjectKeyCache 第 34 到 36 行、
 * 第 48 到 49 行存一个 invalid 标记，TTL 五百毫秒到五秒。注意生产没有用分布式锁解决并发回源，
 * 全仓没有 setIfAbsent 或 SETNX，用的是 generation 版本号加 fence 令牌：写缓存时校验版本号，
 * 版本变了就丢弃这次写入，见 RedisProjectKeyCache 第 56 到 75 行与第 102 到 117 行。
 *
 * <p>【第七，token 为什么先摘要】会话 token 是凭证，明文拼进 key 会原样出现在 redis-cli、
 * 慢查询日志和监控采样里，等于把凭证写进日志。生产 RedisSessionStore 第 91 到 92 行先做
 * SHA-256 再拼 key，第 201 到 210 行就是那个 digest 方法。
 */
@Component
public class RedisLab {

    private static final String PREFIX = "lab:{iam}:";
    private static final DefaultRedisScript<Long> LIMIT = new DefaultRedisScript<>("""
            local count=redis.call('INCR',KEYS[1])
            if count==1 then redis.call('PEXPIRE',KEYS[1],ARGV[1]) end
            return count
            """, Long.class);

    private final StringRedisTemplate redis;

    public RedisLab(StringRedisTemplate redis) { this.redis = redis; }

    public void saveSession(String token, String user, Duration ttl) {
        guard(() -> { redis.opsForValue().set(key("session", token), user, Expiration.milliseconds(ttl.toMillis())); return null; });
    }

    public String session(String token) { return guard(() -> redis.opsForValue().get(key("session", token))); }

    /** 固定窗口限流：一次脚本完成自增与设窗口，第一次自增时设整个窗口。 */
    public boolean allow(String client, int limit, Duration window) {
        var count = guard(() -> redis.execute(LIMIT, List.of(key("rate", client)), Long.toString(window.toMillis())));
        return count != null && count <= limit;
    }

    /** 读穿缓存：命中直接返回；回源为空写五秒负缓存，避免反复穿透。 */
    public String cached(String hash, Duration ttl, Supplier<String> loader) {
        var redisKey = key("key", hash);
        var hit = guard(() -> redis.opsForValue().get(redisKey));
        if (hit != null) return hit.isEmpty() ? null : hit;
        var loaded = loader.get();
        guard(() -> { redis.opsForValue().set(redisKey, loaded == null ? "" : loaded,
                Expiration.milliseconds(loaded == null ? 5_000 : ttl.toMillis())); return null; });
        return loaded;
    }

    /** 依赖不可用时统一转成 503 语义，而不是让原始异常冒成 500。 */
    private <T> T guard(Supplier<T> action) {
        try { return action.get(); } catch (RedisConnectionFailureException failure) {
            throw new Unavailable("会话服务暂不可用");
        }
    }

    /** 三处 key 都带 hash tag，token 与客户端标识都先摘要再进 key。 */
    private static String key(String kind, String id) { return PREFIX + kind + ":" + digest(id); }

    static String digest(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException(missing);
        }
    }

    /** 生产用 BusinessException 加 503 表达同一件事，这里用对应的运行时异常。 */
    public static class Unavailable extends RuntimeException {
        Unavailable(String message) { super(message); }
    }
}
