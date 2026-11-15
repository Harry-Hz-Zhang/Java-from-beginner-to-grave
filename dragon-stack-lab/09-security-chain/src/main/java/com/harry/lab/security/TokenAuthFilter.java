package com.harry.lab.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 控制台令牌认证，对应生产的 infrastructure/security/BearerAuthenticationFilter。
 *
 * <p>第一，继承 OncePerRequestFilter 而不是实现 Filter，因为它保证一次请求只执行一次；
 * forward、include、异步派发都会让请求多次经过同一条链，自己写标记位很容易漏。
 *
 * <p>第二，它只回答「你是谁」，不决定放不放行。放行与否交给 authorizeHttpRequests，
 * 认证在过滤器、授权在配置，这个分工是整条链能读懂的前提。
 *
 * <p>第三，用 shouldNotFilter 分流：服务端接口交给 ProjectKeyAuthFilter，
 * 登录与注册两个接口本来就没有令牌，也不该被这里拦下。
 *
 * <p>第四，带令牌但校验失败时立刻写 401 并 return，不再往下走（fail-fast）。另一种设计是
 * 「失败也继续放行」，由 authenticationEntryPoint 统一返回 401，好处是错误响应只在一处定义；
 * 代价是真正的故障被抹平成「未登录」，还要多绕一层过滤链。生产的取舍是尽早失败，并用
 * SESSION_INVALID 与 SERVICE_UNAVAILABLE 区分「凭证无效」和「鉴权服务挂了」。
 *
 * <p>第五，认证对象用 authenticated(principal, null, authorities) 构造，principal 是一个 record，
 * 控制器就能用 @AuthenticationPrincipal 直接拿到它，不必自己读 ThreadLocal。
 *
 * <p>第六，权限标记是 ROLE_ 加角色名。生产里这类标记没有任何规则消费，角色判断写在应用层
 * （application/AuthorizationApp.java:17-52），这里为了演示 403 才在配置里用 hasAuthority 消费。
 */
@Component
public class TokenAuthFilter extends OncePerRequestFilter {

    /** 认证主体。生产用的是 AuthContext，字段多得多，这里只留演示需要的两项。 */
    record ConsoleUser(Long id, String name) {}

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return ProjectKeyAuthFilter.servicePath(path)
                || ("POST".equals(request.getMethod())
                        && (path.equals("/api/v1/auth/login") || path.equals("/api/v1/auth/register")));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null) {
            chain.doFilter(request, response);
            return;
        }
        if (!header.startsWith("Bearer ") || !"console-token".equals(header.substring(7))) {
            JsonErrors.write(response, 401, "SESSION_INVALID", "会话已失效");
            return;
        }
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new ConsoleUser(1L, "console-user"), null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        SecurityContextHolder.setContext(context);
        chain.doFilter(request, response);
    }
}
