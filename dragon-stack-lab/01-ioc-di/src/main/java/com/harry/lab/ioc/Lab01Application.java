package com.harry.lab.ioc;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * 演示一：控制反转（IoC）与依赖注入（DI）的入口。
 *
 * <p>【@SpringBootApplication 到底是什么】它不是单个注解，而是三个注解的组合。
 * 第一是 @SpringBootConfiguration，它本身是 @Configuration 的派生，表示这是一个配置类。
 * 第二是 @EnableAutoConfiguration，让 Spring Boot 读取所有 starter 里的自动配置清单。
 * 第三是 @ComponentScan，从当前包 com.harry.lab.ioc 开始向下扫描 @Component 系列注解。
 * 记住这三个能力，就能理解为什么启动类必须放在根包：放高了扫不到，放低了会扫到无关的类。
 *
 * <p>【为什么用 @Bean ApplicationRunner 而不是实现 CommandLineRunner】第一，生产项目唯一的
 * 启动钩子是 @Bean ApplicationRunner（config/SecurityConfiguration.java:101-106，
 * 用来做启动时的完整性校验），全仓零 CommandLineRunner。第二，返回 lambda 不会额外占用
 * 一个自身类型的 Bean；如果让启动类去实现接口，容器里就多了一个启动类实例，职责也不清晰。
 * 第三，ApplicationRunner 的 run 方法拿到的是 ApplicationArguments，比字符串数组更好用。
 *
 * <p>【构造器注入为什么优于字段注入】第一，字段可以声明为 final，注入后不可变。
 * 第二，依赖缺失会在启动阶段直接抛异常，而不是运行到那一行才空指针。
 * 生产 TaskRuntimeApp、ContainerApp 全部采用构造器注入，看不到字段上的 @Autowired。
 */
@SpringBootApplication
public class Lab01Application {

    /**
     * 容器启动完成后执行一次。方法参数 OrderService 由容器注入，返回值是一个 lambda，
     * 容器把它登记为 ApplicationRunner 类型的 Bean。
     */
    @Bean
    ApplicationRunner runOnce(OrderService orders) { return args -> orders.place("A-1001"); }

    public static void main(String[] args) {
        SpringApplication.run(Lab01Application.class, args);
    }
}
