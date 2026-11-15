package com.harry.lab.external;

import java.net.URI;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 演示三：起假仓库，跑成功路径、鉴权路径、404 路径与回源路径。
 *
 * <p>【第一，超时值从配置读，默认值就是生产实数】@Value 里写
 * ${lab.registry.connect-timeout:5s}，冒号后面是默认值，Spring 会把 "5s"
 * 转成 Duration。生产项目用 @ConfigurationProperties + @DefaultValue 达到同样效果，
 * 好处是可以集中校验；演示里用 @Value 是为了让取值位置一目了然。
 *
 * <p>【第二，第三个超时只作为数据存在】Timeouts.pull 在演示里没有被使用，
 * 因为它约束的是整次同步的业务总时长，不属于单个 HTTP 请求。
 * 保留这个字段是为了让三个超时的分工在代码里可见。
 *
 * <p>【第三，为什么 finally 里 stop(0)】0 表示立刻关闭，不等现有请求处理完，
 * 这样 JVM 能马上退出。真实服务关闭时要给宽限期（例如 stop(5)），
 * 否则客户端会看到连接被重置；Spring Boot 的 server.shutdown=graceful 同理。
 */
@SpringBootApplication
public class Lab21Application implements CommandLineRunner {

    private static final int PORT = 18092;

    private static final String REPOSITORY = "dragoncloud/dragon_harness";

    private final RegistryClient client;

    public Lab21Application(
            @Value("${lab.registry.connect-timeout:5s}") Duration connect,
            @Value("${lab.registry.read-timeout:30s}") Duration read) {
        this.client = new RegistryClient(URI.create("http://127.0.0.1:" + PORT), REPOSITORY,
                new RegistryClient.Timeouts(connect, read, Duration.ofMinutes(30)));
    }

    @Override
    public void run(String... args) throws Exception {
        var registry = FakeRegistry.registry(PORT);
        var storage = FakeRegistry.storage(PORT + 1);
        try {
            System.out.println("鉴权后 schemaVersion = " + client.schemaVersion("latest"));
            System.out.println("回源收到的凭据     = " + client.blob("sha256:demo"));
            fail(client, "not-exist-tag");
        } finally {
            registry.stop(0);
            storage.stop(0);
            System.out.println("假服务已关闭");
        }
    }

    /** 把领域异常的原因打出来，上层就是按 Reason 决定要不要重试。 */
    private static void fail(RegistryClient client, String tag) {
        try {
            client.schemaVersion(tag);
        } catch (RegistryException ex) {
            System.out.println("失败分支 = " + ex.reason() + " " + ex.getMessage());
        }
    }

    public static void main(String[] args) {
        SpringApplication.run(Lab21Application.class, args);
    }
}
