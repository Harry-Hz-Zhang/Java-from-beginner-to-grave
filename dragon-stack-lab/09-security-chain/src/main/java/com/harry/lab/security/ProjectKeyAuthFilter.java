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
 * 项目服务密钥认证，对应生产的 infrastructure/security/ProjectKeyAuthenticationFilter。
 *
 * <p>第一，它只处理 /api/service/v1 及其子路径，其余请求一律跳过。判断放在 shouldNotFilter 里，
 * 而不是进方法后再写 if，这样被跳过的请求连令牌都不解析。
 *
 * <p>第二，精确路径与子路径必须并列判断。只写 startsWith("/api/service/v1/") 会漏掉不带斜杠的
 * {@code /api/service/v1} 本身，那条请求落到 anyRequest().authenticated() 上，
 * 结果是 401 而不是 403，排查时很容易误判成令牌没生效。
 *
 * <p>第三，这里授予的权限标记是 PROJECT_API，恰好被配置里的 hasAuthority("PROJECT_API") 消费，
 * 所以控制台令牌打服务端接口会停在 401（控制器层根本到不了），项目密钥则能通过。
 *
 * <p>第四，失败同样立刻写响应并 return，不调用 chain.doFilter。错误码用 API_KEY_INVALID，
 * 文案与生产一致，前端据此能分清是密钥问题还是会话问题。
 */
@Component
public class ProjectKeyAuthFilter extends OncePerRequestFilter {

    static final String PREFIX = "/api/service/v1";

    static boolean servicePath(String path) {
        return path.equals(PREFIX) || path.startsWith(PREFIX + "/");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !servicePath(request.getRequestURI().substring(request.getContextPath().length()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ") || !"project-key".equals(header.substring(7))) {
            JsonErrors.write(response, 401, "API_KEY_INVALID", "项目服务密钥无效或已吊销");
            return;
        }
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "demo-project", null, List.of(new SimpleGrantedAuthority("PROJECT_API"))));
        SecurityContextHolder.setContext(context);
        chain.doFilter(request, response);
    }
}
