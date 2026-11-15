package com.harry.lab.stream;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 先用 curl -N 看事件流，再下载文件看流式输出。
 *
 * <pre>
 * mvn -pl 13-sse-streaming spring-boot:run
 * curl --noproxy '*' -N http://localhost:18091/api/v1/events
 * curl --noproxy '*' -OJ http://localhost:18091/api/v1/download
 * </pre>
 *
 * <p>curl 必须加 -N 关闭缓冲，否则你看到的是一堆数据最后一次性打印出来，
 * 看起来像「没有流式效果」，实际是浏览器端缓冲造成的错觉。这个参数是排查 SSE 问题的第一步。
 */
@SpringBootApplication
public class Lab13Application {

    public static void main(String[] args) { SpringApplication.run(Lab13Application.class, args); }
}
