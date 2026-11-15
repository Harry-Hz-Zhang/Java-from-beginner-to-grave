package com.harry.lab.library;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 演示五：同一个 starter 里的第二组配置，前缀是 runner.sdk。
 *
 * <p>【这是什么】一个 starter 往往不止一组配置。生产把「容器运行参数」和
 * 「SDK 限额」拆成两个前缀，是因为它们的使用者不同：前者运维关心，
 * 后者调 SDK 的开发者关心，混在一个类里会让两边都被迫读一堆无关参数。
 *
 * <p>【语法怎么写】@ConfigurationProperties 的前缀用点号分层，写 runner.sdk 即可。
 * 两个配置对象都在自动配置类的 @EnableConfigurationProperties 数组里登记，
 * 绑定器会按前缀分别绑定，互不干扰。
 *
 * <p>【为什么这里需要】不登记就不是 Bean。@ConfigurationProperties 只是标记，
 * 库里也不能指望宿主的 @ConfigurationPropertiesScan 扫到你的包。
 *
 * <p>【生产在哪用】生产的 SdkProperties 管 maxConcurrentRuns、maxRunNotifications、
 * maxRunBytes 三个限额，由 HarnessRunnerCoreAutoConfiguration 的
 * @EnableConfigurationProperties({RunnerProperties.class, SdkProperties.class,
 * SubscriptionProperties.class}) 一起登记。
 */
@ConfigurationProperties("runner.sdk")
public record SdkProperties(
        @DefaultValue("128") int maxConcurrentRuns,
        @DefaultValue("67108864") long maxRunBytes) {
}
