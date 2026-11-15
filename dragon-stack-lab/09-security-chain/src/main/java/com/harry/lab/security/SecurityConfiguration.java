package com.harry.lab.security;

import jakarta.servlet.DispatcherType;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * 控制台与项目服务共用的过滤器链，结构与生产 config/SecurityConfiguration.java 一致。
 *
 * <p>第一，引入 security starter 后默认开启 CSRF、表单登录、HTTP Basic、登出、会话五样东西，
 * 它们面向服务端渲染的网站，对纯 JSON 接口全是负担，所以逐个 disable。CSRF 能关的前提是
 * 用 Bearer 头而不是 Cookie，攻击者拿不到令牌就无法伪造请求。
 *
 * <p>第二，STATELESS 要求每个请求自带凭证，因此 requestCache 也显式关闭。不关的话，
 * 401 的请求仍会被 HttpSessionRequestCache 记进 HttpSession 等着「登录后跳回原地址」，
 * 既白白占用内存，也与无状态语义冲突（生产 :37 就是这样写的）。
 *
 * <p>第三，authorizeHttpRequests 从上往下匹配，第一条命中即生效，所以具体规则必须写在
 * anyRequest 之前。服务端接口有两条并列规则：不带斜杠的精确路径和带斜杠的子路径，
 * 少写前一条会让 {@code /api/service/v1} 落到 anyRequest 上，表现为 401 而不是 403。
 *
 * <p>第四，401 交给 authenticationEntryPoint，403 交给 accessDeniedHandler，两者都走
 * {@link JsonErrors} 写 body，前端据此区分「该重新登录」和「登录了但没权限」。
 *
 * <p>第五，dispatcherTypeMatchers 放行 ASYNC 与 ERROR。它不是用来修 403 的：这里用 setStatus
 * 写响应，根本不会产生 ERROR 派发；留着是为了给异步响应和错误页转发兜底，属于防御性配置。
 *
 * <p>第六，两个自定义过滤器按顺序插入：项目密钥过滤器排在 Bearer 过滤器之前，
 * 两者靠 shouldNotFilter 各管一段路径，互不重叠。
 */
@Configuration
@EnableConfigurationProperties(CorsProperties.class)
public class SecurityConfiguration {

    @Bean SecurityFilterChain security(HttpSecurity http, TokenAuthFilter tokenFilter,
            ProjectKeyAuthFilter projectKeyFilter) throws Exception {
        return http.csrf(c -> c.disable()).cors(Customizer.withDefaults())
                .formLogin(c -> c.disable()).httpBasic(c -> c.disable())
                .logout(c -> c.disable()).requestCache(c -> c.disable())
                .sessionManagement(c -> c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(c -> c
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/register").permitAll()
                        .requestMatchers("/api/v1/audit").hasAuthority("ROLE_AUDITOR")
                        .requestMatchers("/api/service/v1", "/api/service/v1/**").hasAuthority("PROJECT_API")
                        .anyRequest().authenticated())
                .exceptionHandling(c -> c
                        .authenticationEntryPoint((q, s, e) -> JsonErrors.write(s, 401, "SESSION_INVALID", "请先登录"))
                        .accessDeniedHandler((q, s, e) -> JsonErrors.write(s, 403, "FORBIDDEN", "无权执行此操作")))
                .addFilterBefore(tokenFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(projectKeyFilter, TokenAuthFilter.class).build();
    }

    /**
     * CORS 单独说明。第一，允许来源来自配置而不是写死，生产 application.yaml:42 给的是 "*"，
     * 因为控制台与接口通常同域由 nginx 分发。第二，方法与请求头用白名单列出，
     * 比 addAllowedMethod("*") 更贴近生产的实际写法。第三，只对 /api/v1/** 注册，
     * 服务端接口刻意不对浏览器开放；配置写了还必须链上有 .cors(...) 才生效。
     */
    @Bean CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
        var cors = new CorsConfiguration();
        cors.setAllowedOrigins(properties.allowedOrigins());
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
        cors.setMaxAge(Duration.ofHours(1));
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/v1/**", cors);
        return source;
    }

    /**
     * 一个容易被忽略的坑。第一，{@code @Component} 的 Filter 会被 Boot 自动注册到 servlet 容器，
     * 安全链里再 addFilterBefore 就等于注册两次。第二，只是因为 OncePerRequestFilter 自带
     * 「已执行」标记才静默通过，一旦有人换基类或加 @Order，立刻变成双跑，表现为认证执行两遍、
     * 日志重复。第三，生产对两个过滤器都关掉了容器注册（:85-99），这里演示其中一个，写法相同。
     */
    @Bean FilterRegistrationBean<TokenAuthFilter> tokenFilterNotInContainer(TokenAuthFilter filter) {
        var registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
