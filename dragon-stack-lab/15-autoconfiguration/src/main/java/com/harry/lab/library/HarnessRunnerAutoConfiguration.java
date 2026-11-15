package com.harry.lab.library;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.convert.converter.Converter;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 演示三：整个模块的核心，一个最小可用的自动配置类，五个要点各对应一条生产写法。
 *
 * <p>【第一，@AutoConfiguration 与 @Configuration 的区别】功能上都能声明 Bean，
 * 但 @AutoConfiguration 额外标明「这个类来自库」，并支持 before / after 排序。
 * 这里的 before = TaskSchedulingAutoConfiguration.class 和下面的 @EnableScheduling
 * 是一对：本类必须先处理完，Boot 才会因为「已经有人开了调度」而创建 TaskScheduler。
 *
 * <p>【第二，光有注解不够，必须有 imports 清单】Boot 不扫描库的包，
 * 只读 META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports。
 * 文件名改一个字就完全不生效，而且不报错。
 *
 * <p>【第三，static @Bean @ConfigurationPropertiesBinding 为什么必须是 static】
 * 配置绑定发生在 Bean 实例化之前的一步。如果这个 @Bean 方法是实例方法，
 * Spring 为了调用它必须先把自动配置类本身实例化出来，等于在绑定阶段提前创建了
 * 一个还没准备好配置的自动配置对象。static 让容器直接调类方法，绕开这一步。
 * 生产在 HarnessRunnerCoreAutoConfiguration:40-44 就是这么写的。
 *
 * <p>【第四，@DependsOn 的意义】它只保证「被依赖的 Bean 先完成初始化」，
 * 不注入引用。看下面 ContainerRunner 的构造器是空的：如果不写 @DependsOn，
 * 两个 Bean 的初始化先后没有任何保证，第一次容器操作可能跑在数据根锁之前。
 * 生产在 :70 用 @DependsOn("harnessRunnerOwnership") 锁住这个顺序。
 *
 * <p>【第五，@ConditionalOnProperty 的 matchIfMissing】它决定「属性没写时算不算满足」。
 * 不写 matchIfMissing 时默认 false，也就是「不配就关」；写了 true 就是「不配就开」。
 * 生产 :82 用它把监控默认打开，只有显式写 runner.monitor-enabled=false 才关。
 *
 * <p>【第六，@EnableConfigurationProperties 可以登记多组】数组里放了几个类，
 * 就注册几个配置对象。一个 starter 装多组前缀是常态，生产登记了
 * {RunnerProperties, SdkProperties, SubscriptionProperties} 三组。
 */
@AutoConfiguration(before = TaskSchedulingAutoConfiguration.class)
@EnableScheduling
@EnableConfigurationProperties({RunnerProperties.class, SdkProperties.class})
public class HarnessRunnerAutoConfiguration {

    @Bean
    @ConfigurationPropertiesBinding
    static PathConverter harnessRunnerPathConverter() {
        return new PathConverter();
    }

    @Bean
    @ConditionalOnMissingBean
    GreetingService greetingService(RunnerProperties properties, SdkProperties sdk) {
        return new GreetingService(properties, sdk);
    }

    @Bean
    DataRootLock harnessRunnerDataRootLock(RunnerProperties properties) {
        return new DataRootLock(properties.dataRoot());
    }

    @Bean
    @DependsOn("harnessRunnerDataRootLock")
    ContainerRunner harnessRunnerContainerRunner() {
        return new ContainerRunner();
    }

    @Bean
    @ConditionalOnProperty(name = "runner.monitor-enabled", havingValue = "true", matchIfMissing = true)
    MonitorTask harnessRunnerMonitorTask(RunnerProperties properties, ObjectProvider<TaskScheduler> scheduler) {
        return new MonitorTask(properties, scheduler);
    }

    @Bean
    @ConditionalOnProperty(name = "runner.debug", havingValue = "true")
    DebugReporter debugReporter(RunnerProperties properties) {
        return new DebugReporter(properties);
    }

    /** 把配置里的字符串转成 Path。不注册它，dataRoot 这种类型会在绑定阶段直接失败。 */
    static final class PathConverter implements Converter<String, Path> {
        @Override
        public Path convert(String source) {
            return Path.of(source);
        }
    }

    /** 模拟生产的数据根租约：先拿到根目录，后面的容器操作才有地方落盘。 */
    static final class DataRootLock {
        private final Path root;

        DataRootLock(Path root) {
            this.root = root;
        }

        @PostConstruct
        void acquire() throws IOException {
            Files.createDirectories(root);
            System.out.println("[lock] 已取得数据根锁 " + root.toAbsolutePath());
        }
    }

    /** 模拟第一次容器操作。构造器刻意不接收 DataRootLock，顺序完全由 @DependsOn 保证。 */
    static final class ContainerRunner {
        @PostConstruct
        void firstOperation() {
            System.out.println("[container] 第一次容器操作，此时数据根锁已就绪");
        }
    }

    /** 只在开关打开时才存在的可选组件。 */
    public static class DebugReporter {
        private final RunnerProperties properties;

        DebugReporter(RunnerProperties properties) {
            this.properties = properties;
        }

        public String report() {
            return "调试模式已开启，当前镜像 " + properties.image();
        }
    }
}
