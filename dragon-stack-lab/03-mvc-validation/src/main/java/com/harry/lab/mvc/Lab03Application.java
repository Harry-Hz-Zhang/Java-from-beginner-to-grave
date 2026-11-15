package com.harry.lab.mvc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 启动这个类之后，内嵌 Tomcat 会监听 8080 端口，你可以直接用 curl 试接口。
 *
 * <p>【为什么这里什么都不用写】第一，@SpringBootApplication 已经包含组件扫描，
 * 同包下的 @RestController 与 @RestControllerAdvice 都会被自动注册。第二，Web 服务器与
 * Jackson 序列化器由 starter-webmvc 带来的自动配置负责创建，不需要手写 XML 或 Java 配置
 * 去注册 DispatcherServlet。
 *
 * <p>【验证方式】见本模块 README 里的 curl 命令，覆盖正常创建（202）、校验失败（400）、
 * 多传字段（400）、查询成功（200）、查询不存在（404）、参数越界（400）、方法不支持（405）。
 */
@SpringBootApplication public class Lab03Application {

    public static void main(String[] args) { SpringApplication.run(Lab03Application.class, args); }
}
