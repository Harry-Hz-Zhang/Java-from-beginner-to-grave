package com.harry.lab.testing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 启动类，给两个测试类提供 @SpringBootConfiguration 入口。
 *
 * <p>【第一，为什么两组的启动方式不同】TaskControllerWebTests 用 @SpringBootTest，
 * 需要完整的自动配置；TaskControllerSliceTests 用 @WebMvcTest，只需要找到
 * 这个配置类作为上下文根。同一个启动类同时服务两者，说明测试策略的差别
 * 完全体现在注解上，而不是工程结构上。
 *
 * <p>【第二，运行方式】mvn -pl 20-testing-strategy test。
 * 两组共五条用例：全量启动三条，切片两条。
 */
@SpringBootApplication
public class Lab20Application {

    public static void main(String[] args) {
        SpringApplication.run(Lab20Application.class, args);
    }
}
