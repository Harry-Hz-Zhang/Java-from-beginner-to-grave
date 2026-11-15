package com.harry.lab.security;

import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 几个接口覆盖过滤器链的不同分支。
 *
 * <p>第一，登录与注册在配置里用 HttpMethod.POST 限定放行，所以只有 POST 免认证，
 * 用 GET 打同一个路径仍然 401，这是「方法限定」这条规则最直观的验证方式。
 *
 * <p>第二，@AuthenticationPrincipal 直接把过滤器放进上下文的 record 注进来，
 * 不需要自己读 SecurityContextHolder，也不会因为拿错上下文而取到空值。
 *
 * <p>第三，/api/v1/audit 要求 ROLE_AUDITOR，控制台用户只有 ROLE_ADMIN，
 * 这是唯一能稳定复现 403 的分支：请求已认证，但权限不足。
 * 服务端接口做不到这一点，因为项目密钥过滤器在授权之前就把它拦掉了。
 *
 * <p>第四，/api/service/v1 与 /api/service/v1/ping 两个映射对应配置里的两条并列规则，
 * 用来验证带斜杠和不带斜杠都必须显式写出。
 */
@RestController
@RequestMapping("/api")
class DemoController {

    @PostMapping({"/v1/auth/login", "/v1/auth/register"})
    Map<String, String> auth() { return Map.of("token", "console-token"); }

    @GetMapping("/v1/me")
    Map<String, Object> me(@AuthenticationPrincipal TokenAuthFilter.ConsoleUser user) {
        return Map.of("id", user.id(), "name", user.name());
    }

    @GetMapping("/v1/audit")
    String audit() { return "审计接口"; }

    @GetMapping({"/service/v1", "/service/v1/ping"})
    String service() { return "服务端接口可访问"; }
}
