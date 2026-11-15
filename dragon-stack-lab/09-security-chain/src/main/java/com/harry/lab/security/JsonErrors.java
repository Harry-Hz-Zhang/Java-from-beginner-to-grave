package com.harry.lab.security;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 统一写出安全层的错误响应，对应生产的 infrastructure/security/HttpErrorWriter。
 *
 * <p>第一，这里刻意不用 {@code sendError}。它把请求交给容器去找错误页，最终 body 变成
 * Spring Boot 默认的 {timestamp, status, error, path}，你写进去的 message 还会被
 * {@code server.error.include-message=never} 抑制掉，前端拿不到任何业务错误码。
 *
 * <p>第二，改用 setStatus 加手写 body，响应体就是生产那套 {code, message, data} 三段信封
 * （见生产 controller/vo/WebResponse.java:3），而且不会触发 ERROR 派发。
 *
 * <p>第三，401 额外带 WWW-Authenticate: Bearer，这是规范对 Bearer 认证的要求，
 * 缺失时部分客户端不会把响应识别成「需要重新认证」。
 *
 * <p>第四，生产这个类是有状态的 Bean（依赖 ObjectMapper），demo 里简化为静态方法，
 * 好处是过滤器和配置类都不必为它写构造器，调用点也更集中。
 */
final class JsonErrors {

    static void write(HttpServletResponse res, int status, String code, String message) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json");
        res.setCharacterEncoding("UTF-8");
        if (status == 401) res.setHeader("WWW-Authenticate", "Bearer");
        res.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\",\"data\":null}");
    }
}
