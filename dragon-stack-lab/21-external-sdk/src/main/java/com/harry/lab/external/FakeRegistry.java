package com.harry.lab.external;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * 演示二：用 JDK 自带的 HttpServer 造两个假服务。
 *
 * <p>【第一，为什么造假服务而不是调真 ACR】真仓库需要账号密码，把凭据写进 demo
 * 就是提交密码；网络不通时 demo 跑不起来，学习体验被环境问题主导；
 * 真实服务的行为你控制不了，而演示 401 与 404 分支需要它按预期返回。
 *
 * <p>【第二，为什么要两个服务】仓库会把 blob 下载重定向到对象存储，
 * 那是另一个地址。只有把「仓库」和「回源」分成两个监听端口，
 * 才能演示「跨 authority 时不转发 Authorization」这条安全规则。
 * 存储服务会把收到的 Authorization 头原样写进响应体，
 * 于是调用方一眼就能看出凭据有没有被带过去。
 *
 * <p>【第三，401 必须带 WWW-Authenticate】只返回状态码，客户端无从知道该去哪里换票。
 * 真实仓库在响应头里写 realm、service、scope 三个参数，本 demo 照此返回，
 * 客户端据此拼出 token 请求。
 *
 * <p>【第四，com.sun.net.httpserver 只适合做测试替身】它没有连接池、没有超时控制、
 * 性能也差，生产环境不要用；但它零依赖，用来验证协议细节非常合适。
 */
public final class FakeRegistry {

    private static final String MANIFEST =
            "{\"schemaVersion\":2,\"mediaType\":\"application/vnd.docker.distribution.manifest.v2+json\"}";

    private FakeRegistry() {
    }

    /** 镜像仓库：/token 换票，/v2/ 下的清单与 blob。 */
    public static HttpServer registry(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/token", exchange -> reply(exchange, 200, "{\"token\":\"demo-token\"}"));
        server.createContext("/v2/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (auth == null) {
                exchange.getResponseHeaders().add("WWW-Authenticate", "Bearer realm=\"http://127.0.0.1:" + port
                        + "/token\",service=\"registry\",scope=\"repository:dragoncloud/dragon_harness:pull\"");
                reply(exchange, 401, "{\"errors\":[{\"code\":\"UNAUTHORIZED\"}]}");
            } else if (path.endsWith("/manifests/latest")) {
                reply(exchange, 200, MANIFEST);
            } else if (path.contains("/blobs/")) {
                exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + (port + 1) + "/oss/blob");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            } else {
                reply(exchange, 404, "{\"errors\":[{\"code\":\"MANIFEST_UNKNOWN\"}]}");
            }
        });
        server.start();
        return server;
    }

    /** 对象存储：把收到的 Authorization 头原样回显，用来验证回源没有带凭据。 */
    public static HttpServer storage(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/oss/", exchange -> reply(exchange, 200,
                "{\"authorization\":\"" + String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")) + "\"}"));
        server.start();
        return server;
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
