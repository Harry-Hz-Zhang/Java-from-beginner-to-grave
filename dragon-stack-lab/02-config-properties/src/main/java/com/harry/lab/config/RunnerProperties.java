package com.harry.lab.config;

import jakarta.validation.constraints.*;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.*;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * 演示一：用 record 做配置绑定，并演示可选值、必填项、两种校验与脱敏打印。
 * 这份类和 harness-runner 的 RunnerProperties 是同一套写法。
 *
 * <p>【@ConfigurationProperties 干什么】第一，把 application.yaml 里以 runner 开头的一整棵
 * 配置树按参数名映射到对象上，kebab-case 会自动对应驼峰。第二，和 @Value 的区别是它能一次
 * 绑一组，支持嵌套对象、集合、Duration、DataSize、Path、URI 等类型自动转换，IDE 也能提示。
 *
 * <p>【为什么用 record】record 的参数就是构造器参数，Spring 走「构造器绑定」，
 * 对象天然不可变。普通类如果有无参构造器和 setter，Spring 会改走 JavaBean 绑定，那是另一套语义。
 *
 * <p>【@DefaultValue 与必填项】第一，@DefaultValue 标在 record 组件上，yaml 里没有这个键时才生效。
 * 第二，不标 @DefaultValue 且类型是对象时，这个键就是必填的，缺了会在绑定阶段失败。
 * 生产 RunnerProperties 里的 environmentFile 就是这种必填 String，本类照抄了一份。
 * 第三，基本类型 int 缺键时会被绑成 0，所以必须配 @Min 之类的约束，否则 0 会被当成合法值。
 *
 * <p>【单值也能绑成列表】allowed-origins 声明为 List&lt;String&gt;，yaml 里写
 * allowed-origins: http://localhost:5173 这样的单值，Spring 会自动包成单元素列表；
 * 写成短横线数组则按顺序绑成多元素列表。两种写法见 application.yaml 与 application-prod.yaml。
 *
 * <p>【两种校验并存】第一，字段上的 @NotNull、@Min 管单值边界，由 @Validated 触发，
 * 抛的是 ConstraintViolationException。第二，紧凑构造器管跨字段关系，例如生产
 * config/TaskProperties.java:44-46 要求 eventRetainedBytes 不小于 eventReplayBytes，
 * 单个字段各自合法但放在一起不合法，只能在这里判断。本类的跨字段规则是
 * maxRunBytes 必须覆盖住 maxWorkspaceSize。
 *
 * <p>【为什么要重写 toString】配置对象经常被日志打印，默认的 record toString 会把所有组件
 * 原样输出。生产 config/OssProperties.java:45-48 只打印 applicationName 与 bucket，
 * config/ProjectApiProperties.java:19-22 只打印 cacheTtl，都把密钥类字段排除在外。
 * 本类只输出非敏感项，environmentFile 可能指向含密钥的文件，故意不打印。
 */
@ConfigurationProperties("runner") @Validated
public record RunnerProperties(
        @DefaultValue("dragon-harness:local") String image, @DefaultValue("../data/runner") Path dataRoot,
        @DefaultValue("10s") Duration monitorInterval, @DefaultValue("16777216") int maxFrameBytes,
        @DefaultValue("16MB") DataSize maxWorkspaceSize,
        @DefaultValue("https://oss-cn-heyuan.aliyuncs.com") URI endpoint,
        @DefaultValue("http://localhost:5173") List<String> allowedOrigins,
        long maxRunBytes, String environmentFile,
        @NotNull Duration shutdownTimeout, @DefaultValue("4") @Min(1) int maxConcurrentRuns) {

    /**
     * 紧凑构造器：绑定完成后、对象交给别人之前执行。第一，这里抛异常等于启动失败，
     * 避免带着错误配置跑起来。第二，它先于 Bean Validation 执行，所以判空要自己写。
     */
    @ConstructorBinding
    public RunnerProperties {
        if (monitorInterval == null || monitorInterval.isNegative()) throw new IllegalArgumentException("runner.monitor-interval 不能为负");
        if (maxFrameBytes < 1) throw new IllegalArgumentException("runner.max-frame-bytes 必须为正整数");
        if (maxWorkspaceSize == null || maxWorkspaceSize.toBytes() < 1 || maxRunBytes < maxWorkspaceSize.toBytes())
            throw new IllegalArgumentException("runner.max-run-bytes 必须覆盖 max-workspace-size");
        if (allowedOrigins == null || allowedOrigins.isEmpty()) throw new IllegalArgumentException("runner.allowed-origins 至少一个");
        if (shutdownTimeout == null) throw new IllegalArgumentException("runner.shutdown-timeout 必填");
    }

    /** 只打印可以进日志的项。environmentFile 可能指向含密钥的文件，故意不输出。 */
    @Override
    public String toString() {
        return "RunnerProperties[image=" + image + ", dataRoot=" + dataRoot + ", monitorInterval=" + monitorInterval
                + ", maxWorkspaceSize=" + maxWorkspaceSize + ", endpoint=" + endpoint + ", allowedOrigins=" + allowedOrigins
                + ", maxRunBytes=" + maxRunBytes + ", shutdownTimeout=" + shutdownTimeout + "]";
    }
}