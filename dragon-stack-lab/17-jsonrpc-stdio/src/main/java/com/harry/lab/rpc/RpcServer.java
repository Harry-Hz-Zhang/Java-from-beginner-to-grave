package com.harry.lab.rpc;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 演示一：一个跑在子进程里的最小 RPC 服务，只从标准输入读、往标准输出写。
 *
 * <p>【这是什么】JSON-RPC 2.0 的服务端一侧。生产里这一侧不是这个类，
 * 而是容器内 DSH 自己的实现；本类只负责把协议行为演出来：正常 result、
 * 错误帧 error、通知（无 id，不回复）、以及服务端主动发的反向请求。
 *
 * <p>【为什么走 stdio 而不是端口】容器内进程没有对外暴露端口，
 * 唯一稳定可连的就是 stdin 和 stdout。代价是不能多路复用，所以必须
 * 「一行一个 JSON」，用换行符切帧。
 *
 * <p>【第一，未知方法必须回 error 帧，不能沉默】JSON-RPC 2.0 规定
 * 找不到方法时返回 code = -32601（Method not found）。客户端只有收到这个
 * 明确的帧才能区分「对方不认识这个方法」和「对方卡住了」。
 * 生产客户端 JsonLineRpcConnection:231-246 就是按 error.code 建异常的。
 *
 * <p>【第二，没有 id 的请求是通知，不要回复】回一个 id 为 null 的 result
 * 会让对端把它当成无法匹配的响应；协议上通知本来就不期待回复。
 *
 * <p>【第三，服务端也可以主动发请求】只要有 id，就是一次反向请求。
 * 生产客户端对此的策略是统一回 -32601 "Server-to-client requests are not supported"
 * （:211-222），而不是静默丢弃——静默丢弃会让对端永远等不到结果。
 *
 * <p>【为什么 writer 要 autoflush】PrintWriter 的第二个参数为 true 时，
 * println 结束就 flush。不 flush，帧会卡在缓冲区里，对端 readLine 永远等不到。
 */
public class RpcServer {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public static void main(String[] args) throws Exception {
        var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        var writer = new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true);
        writer.println(frame(Map.of("method", "server/ready", "params", Map.of("version", "demo-1.0"))));
        System.err.println("token=sk-demo-credential（只用来演示 stderr 环形缓冲）");

        String line;
        while ((line = reader.readLine()) != null) {
            var request = JSON.readTree(line);
            JsonNode id = request.get("id");
            if (id == null || id.isNull()) continue;
            var method = request.get("method").asText();
            if ("trigger/server-request".equals(method)) {
                writer.println(frame(Map.of("id", "srv-1", "method", "server/hello",
                        "params", Map.of("text", "来自服务端的反向请求"))));
            }
            var response = switch (method) {
                case "ping" -> reply(id, "pong");
                case "prepare" -> reply(id, "prepared:" + request.get("params").get("text").asText());
                case "trigger/server-request" -> reply(id, "已发出一次反向请求");
                case "slow" -> {
                    Thread.sleep(1_500);
                    yield reply(id, "slow done");
                }
                default -> error(id, -32601, "Method not found: " + method);
            };
            writer.println(response);
        }
    }

    private static String reply(JsonNode id, String result) {
        return frame(Map.of("id", id, "result", result));
    }

    private static String error(JsonNode id, int code, String message) {
        return frame(Map.of("id", id, "error", Map.of("code", code, "message", message)));
    }

    private static String frame(Map<String, Object> body) {
        var frame = new java.util.LinkedHashMap<String, Object>();
        frame.put("jsonrpc", "2.0");
        frame.putAll(body);
        return JSON.writeValueAsString(frame);
    }
}
