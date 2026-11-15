package com.harry.lab.security;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

/**
 * 启动后按 README 里的 curl 清单逐条验证。
 *
 * <p>第一，这里必须排除 UserDetailsServiceAutoConfiguration。引入 security starter 之后，
 * 只要容器里没有 UserDetailsService，它就会注册一个内存用户，并在启动日志里打印
 * 「Using generated security password: ...」。这个用户在本 demo 里毫无用处，
 * 却会让人误以为登录流程是 Spring 提供的。
 *
 * <p>第二，排除它之后，全部认证逻辑只存在于两个自定义过滤器里，读代码时不会有第二套入口。
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class Lab09Application {

    public static void main(String[] args) { SpringApplication.run(Lab09Application.class, args); }
}
