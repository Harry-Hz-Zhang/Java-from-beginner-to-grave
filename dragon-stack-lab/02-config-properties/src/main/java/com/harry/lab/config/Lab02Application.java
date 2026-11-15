package com.harry.lab.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 演示四：把配置对象交给容器，并用 profile 与 config.import 做环境组合。
 *
 * <p>【@EnableConfigurationProperties 的作用】第一，@ConfigurationProperties 只是一个标记，
 * 标了它不会自动变成 Bean。第二，两种注册方式是 @EnableConfigurationProperties(XXX.class)
 * 显式登记，或在启动类上写 @ConfigurationPropertiesScan 扫包。生产用的是前者：
 * HarnessRunnerCoreAutoConfiguration 上写着 @EnableConfigurationProperties({...})。
 *
 * <p>【为什么生产选显式登记】第一，自动配置类要精确控制「哪些配置对象在这一层被注册」，
 * 扫描是碰运气，显式登记是可预期。第二，你写自己的 starter 时也应该用显式登记。
 *
 * <p>【profile 与 import 两个机制】第一，spring.profiles.default 指定没有显式激活时
 * 默认用哪个 profile，生产写的是 local，本模块写 prod 是为了让基线运行也能看到覆盖效果。
 * 第二，spring.config.import 把另一个配置文件挂进来，生产用它挂 application-oss.yaml，
 * 好处是 OSS 相关配置集中在一个文件里，主配置只留一行入口。
 *
 * <p>【环境变量占位符】${ENV_NAME:default} 的冒号前是环境变量名，冒号后是取不到时的默认值。
 * 生产 application.yaml 里的 ${HARNESS_TASK_RUNTIME_IDLE_TTL:10m} 就是这个写法。
 */
@SpringBootApplication
@EnableConfigurationProperties(RunnerProperties.class)
public class Lab02Application {

    public static void main(String[] args) {
        var p = SpringApplication.run(Lab02Application.class, args).getBean(RunnerProperties.class);
        // 用一次 printf 打完，避免十行 println 把代码总量顶出纪律
        System.out.printf("镜像=%s%n数据目录=%s%n监控间隔=%ds%n工作区上限=%dMB%n端点=%s%n来源=%s%n并发=%d%n脱敏=%s%n",
                p.image(), p.dataRoot(), p.monitorInterval().toSeconds(), p.maxWorkspaceSize().toMegabytes(),
                p.endpoint(), p.allowedOrigins(), p.maxConcurrentRuns(), p);
    }
}
