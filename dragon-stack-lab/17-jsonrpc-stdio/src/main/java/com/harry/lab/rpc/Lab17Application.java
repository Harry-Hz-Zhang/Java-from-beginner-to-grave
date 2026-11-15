package com.harry.lab.rpc;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 启动一个子进程当服务端，依次验证：正常 result、错误帧、请求超时、
 * 服务端反向请求、通知回调、stderr 只留在内存。
 *
 * <p>【观察点一】超时那次是 1.5 秒的方法配 300 毫秒超时。超时之后服务端
 * 仍在跑，它稍后写回的响应会因为没有对应 pending 而被忽略——这就是
 * 「超时只放弃本地关联，对方的工作仍在跑」。
 *
 * <p>【观察点二】服务端启动时先发一条 server/ready 通知，没有 id。
 * 它被交给回调打印，不会进入 pending 表，也不会影响任何一次请求。
 *
 * <p>【观察点三】reverse 请求的 id 是 srv-1，不是 req_ 开头，客户端据此
 * 判定为反向请求并回 -32601，而不是把它当自己的响应错配给某个调用。
 */
@SpringBootApplication
public class Lab17Application implements CommandLineRunner {

    @Override
    public void run(String... args) throws Exception {
        try (var client = new StdioRpcClient(RpcServer.class,
                note -> System.out.println("[notification] " + note))) {
            System.out.println("第一种调用 = " + client.call("ping", Map.of("text", "hello"), Duration.ofSeconds(2)));
            System.out.println("第二种调用 = " + client.call("prepare", Map.of("text", "world"), Duration.ofSeconds(2)));

            try {
                client.call("shell/exec", Map.of(), Duration.ofSeconds(2));
            } catch (StdioRpcClient.RpcException ex) {
                System.out.println("错误帧     = code=" + ex.code() + "，" + ex.getMessage());
            }

            try {
                client.call("slow", Map.of(), Duration.ofMillis(300));
            } catch (StdioRpcClient.RpcException ex) {
                System.out.println("超时分支   = code=" + ex.code() + "，" + ex.getMessage());
            }

            System.out.println("反向请求   = " + client.call("trigger/server-request", Map.of(), Duration.ofSeconds(2)));
            System.out.println("stderr 尾部 = " + client.diagnostics().strip().length() + " 字节，只在内存里");
        }
        System.out.println("客户端已关闭，子进程被销毁");
    }

    public static void main(String[] args) {
        SpringApplication.run(Lab17Application.class, args);
    }
}
