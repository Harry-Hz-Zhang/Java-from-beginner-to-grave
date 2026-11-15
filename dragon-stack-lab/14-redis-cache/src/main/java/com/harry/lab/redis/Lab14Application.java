package com.harry.lab.redis;

import java.time.Duration;
import java.util.concurrent.locks.LockSupport;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 依次验证会话、限流、负缓存、读穿缓存四种行为。
 *
 * <p>【这是什么】一个命令行应用，CommandLineRunner 在启动后跑一遍，跑完容器退出。
 * 它演示调用方视角：每个方法对应生产里的一个用例，输出就是验收标准。
 *
 * <p>【第一，限流为什么要换客户端标识】计数器有六十秒的键，沿用同一个标识第二次运行
 * 前三次会直接被拦。这是写限流测试最容易踩的坑：测试之间没有隔离。
 *
 * <p>【第二，为什么负缓存要数回源次数】查一个不存在的键，第一次回源返回空，
 * 负缓存把它记下来，第二次就不该再回源。打印计数是一眼看穿缓存是否生效的最短路径，
 * 比看耗时可靠，因为耗时会被机器负载干扰。
 *
 * <p>【第三，Redis 不可用时输出什么】捕获 Unavailable 后打印 503 SERVICE_UNAVAILABLE，
 * 与生产 GlobalExceptionHandler 把 DataAccessException 转 503 对应。不接住的话异常会冒成 500，
 * 调用方会误以为自己请求写错了。
 */
@SpringBootApplication
public class Lab14Application implements CommandLineRunner {

    private final RedisLab redis;

    public Lab14Application(RedisLab redis) { this.redis = redis; }

    public void run(String... args) {
        try {
            redis.saveSession("token-abc", "harry", Duration.ofMinutes(30));
            System.out.println("会话内容 = " + redis.session("token-abc"));
            var client = "console-" + System.currentTimeMillis();
            var allowed = new StringBuilder();
            for (int i = 1; i <= 5; i++) allowed.append(redis.allow(client, 3, Duration.ofSeconds(60))).append(' ');
            System.out.println("五次请求放行情况 = " + allowed);
            var loads = new int[1];
            for (int i = 0; i < 2; i++) redis.cached("missing", Duration.ofSeconds(30), () -> { loads[0]++; return null; });
            var start = System.currentTimeMillis();
            redis.cached(client, Duration.ofSeconds(30), () -> { LockSupport.parkNanos(300_000_000L); return "local"; });
            var first = System.currentTimeMillis() - start;
            start = System.currentTimeMillis();
            redis.cached(client, Duration.ofSeconds(30), () -> "local");
            System.out.println("不存在键回源次数 = " + loads[0] + "，首次加载 = " + first
                    + " 毫秒，第二次 = " + (System.currentTimeMillis() - start) + " 毫秒");
        } catch (RedisLab.Unavailable unavailable) {
            System.out.println("→ 503 SERVICE_UNAVAILABLE：" + unavailable.getMessage());
        }
    }

    public static void main(String[] args) { SpringApplication.run(Lab14Application.class, args); }
}
