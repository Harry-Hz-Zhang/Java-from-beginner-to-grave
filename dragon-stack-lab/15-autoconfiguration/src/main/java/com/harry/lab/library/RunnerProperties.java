package com.harry.lab.library;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 演示一：库自己的配置对象，对应生产的 RunnerProperties。
 *
 * <p>【这是什么】一个 record 形式的配置绑定目标。前缀 runner 之下所有属性都绑到构造参数上，
 * 没写的用 @DefaultValue 兜底，宿主因此不必了解库需要哪些参数。
 *
 * <p>【语法怎么写】record 加 @ConfigurationProperties("runner")，参数名即属性名，
 * kebab-case 会自动匹配（monitor-interval 绑到 monitorInterval）。
 * 参数上的 @DefaultValue 是「绑定阶段」的默认值，和 Java 字段初始值不是一回事。
 *
 * <p>【为什么这里需要 Path 类型】因为要演示第二点的转换器。
 * 配置文件里只能写字符串，Path 这种类型必须靠 Converter 转换，绑定器不会自己猜。
 *
 * <p>【生产在哪用】生产的 RunnerProperties 有十九个参数，包括 dockerExecutable、
 * dataRoot（Path）、dockerTimeout、pullTimeout 等；它在
 * HarnessRunnerCoreAutoConfiguration 里通过 @EnableConfigurationProperties 登记。
 */
@ConfigurationProperties("runner")
public record RunnerProperties(
        @DefaultValue("dragon-harness:local") String image,
        @DefaultValue("10s") Duration monitorInterval,
        @DefaultValue("false") boolean debug,
        @DefaultValue("./target/data") Path dataRoot) {
}
