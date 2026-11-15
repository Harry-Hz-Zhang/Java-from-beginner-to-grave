package com.harry.lab.library;

/**
 * 演示二：库里的普通业务类。注意类上没有任何 Spring 注解。
 *
 * <p>【这是什么】一个纯 POJO，构造器接收两个配置对象，由自动配置类的 @Bean 方法创建。
 *
 * <p>【语法怎么写】不要写 @Component。库里的类一旦标了组件注解，
 * 宿主扫描到就注册一次、自动配置再注册一次，最后报 NoUniqueBeanDefinitionException。
 *
 * <p>【为什么这里需要】它是「自动配置确实生效」的证据：能注入出这个 Bean，
 * 说明 imports 清单被读到、@Bean 方法被执行了。
 *
 * <p>【生产在哪用】生产里同样形态的类是 DockerCliContainerGateway 和 ContainerApp，
 * 都是普通类，只在 HarnessRunnerCoreAutoConfiguration 里用 @Bean 注册。
 */
public class GreetingService {

    private final RunnerProperties properties;
    private final SdkProperties sdk;

    public GreetingService(RunnerProperties properties, SdkProperties sdk) {
        this.properties = properties;
        this.sdk = sdk;
    }

    public String greet() {
        return "Runner 已就绪，镜像=" + properties.image()
                + "，监控间隔=" + properties.monitorInterval().toSeconds() + " 秒"
                + "，数据根=" + properties.dataRoot()
                + "，SDK 并发上限=" + sdk.maxConcurrentRuns();
    }
}
