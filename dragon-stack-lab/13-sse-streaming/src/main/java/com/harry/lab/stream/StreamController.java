package com.harry.lab.stream;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.LockSupport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * 演示一：事件流要显式声明字符集、要有心跳、要清理订阅者。
 * 演示二：下载要声明文件名编码、长度与兜底类型。
 *
 * <p>【这是什么】两个接口共用一个控制器。第一个把运行日志一条条推给浏览器，用的是 SSE；
 * 第二个把工作区文件边读边写回去，用的是 StreamingResponseBody。
 *
 * <p>【第一，Content-Type 必须带 charset】只用 produces = text/event-stream 是不够的。
 * SseEmitter 在响应头还没写死时会填一个不带 charset 的默认值，浏览器按 Latin-1 解码，
 * 中文就变成乱码。正确做法是返回 ResponseEntity，用 new MediaType(TEXT_EVENT_STREAM, UTF_8)
 * 显式声明，这个 MediaType 要在 emitter 交给容器之前设好。生产 controller/task/TaskEventStreams.java
 * 第 25 行与第 71 到 73 行就是这么写的，起因是一次真实的线上中文乱码事故，
 * 记录在 dragon_harness/harness-runner/docs/VERIFICATION.md 第 32 到 34 行，修复时有专门的
 * 字符集测试覆盖中文与 emoji。
 *
 * <p>【第二，为什么必须发心跳】长连接上长时间没有数据，中间的 Nginx、网关或负载均衡会按
 * 空闲超时把连接掐掉，客户端看到的是「莫名其妙断线」。心跳是一个 comment 帧，
 * 即 SseEmitter.event().comment(...)，协议上以冒号开头，浏览器收到后直接忽略，不触发
 * 任何事件回调，但连接因此一直有字节流动。生产 TaskEventStreams 第 36 到 41 行在做这件事，
 * 间隔取 config/TaskProperties.java 里的 eventHeartbeat，默认十五秒。
 *
 * <p>【第三，三个回调为什么必须注册】客户端关页面、代理超时、写失败都会走到
 * onCompletion、onTimeout、onError。不注册的话服务端集合里那个 emitter 引用永远不释放，
 * 连接越多人越占内存，就是典型的连接泄漏。生产 TaskEventStreams 第 31 到 33 行注册这三个回调，
 * TaskEventHub 第 130 行与第 153 到 156 行在回调里把订阅者从 channel 上摘掉。
 *
 * <p>【第四，send 不设 id】生产 send 只给事件名和数据（TaskEventStreams 第 67 到 69 行），
 * 不设 id，也没有 Last-Event-ID 重放。断线恢复靠的是服务端 replay 缓冲：每个 run 保留
 * 上限一千条与三十二兆字节的事件，订阅队列容量两千零四十八，队列满时投递一个
 * 表示流已终止的事件并结束订阅，客户端据此重新订阅，再由终态补偿拿到最终状态。
 * 所以本模块也不演示 id。
 *
 * <p>【第五，结束用终态而不是自定义 done】生产推的是 run.status 这种终态事件，
 * 收到它前端就知道流要结束了，服务端随即关闭流。自定义一个 event: done 只能表达「我说完了」，
 * 表达不了「结果是成功还是失败」，前端还得再看一条消息。
 *
 * <p>【第六，下载为什么要这三样】ContentDisposition 用 attachment().filename(名字, UTF_8)
 * 生成，中文文件名不加 UTF-8 会乱码；contentLength 让浏览器显示进度并判断是否下载完整；
 * produces = ALL_VALUE 表示不预先锁定类型，真正的类型由方法里返回的 MediaType 决定。
 * 未知类型兜底成 application/octet-stream，浏览器就会当二进制下载而不是尝试渲染。
 * 生产 controller/task/TaskFileController.java 第 44 到 52 行、第 64 到 71 行就是这个写法。
 *
 * <p>【第七，异常处理】流一旦开始写，响应头和状态码就固定了，此时再抛异常也无法回 500。
 * 客户端提前断开后再往响应里写会抛 AsyncRequestNotUsableException，见 StreamExceptionHandler。
 */
@RestController
@RequestMapping("/api/v1")
public class StreamController {

    /** 当前活着的订阅者。生产里由 TaskEventHub 按 run 分组管理，这里用扁平集合演示清理动作。 */
    private static final Set<SseEmitter> SUBSCRIBERS = ConcurrentHashMap.newKeySet();
    private static final MediaType EVENT_STREAM = new MediaType(MediaType.TEXT_EVENT_STREAM, StandardCharsets.UTF_8);

    /** 心跳间隔，生产默认十五秒，本模块的 yaml 调成一秒便于观察。 */
    @Value("${lab.stream.heartbeat:15s}")
    private Duration heartbeat;

    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> events() {
        var emitter = new SseEmitter(0L);
        SUBSCRIBERS.add(emitter);
        emitter.onCompletion(() -> drop(emitter, "onCompletion"));
        emitter.onTimeout(() -> drop(emitter, "onTimeout"));
        emitter.onError(error -> drop(emitter, "onError"));
        Thread.ofVirtual().name("sse-push").start(() -> push(emitter));
        return ResponseEntity.ok().contentType(EVENT_STREAM).body(emitter);
    }

    /** 推送线程：三条日志、两拍心跳、一条终态，然后关流。 */
    private void push(SseEmitter emitter) {
        try {
            for (int i = 1; i <= 3; i++) {
                emitter.send(SseEmitter.event().name("run.log").data("第 " + i + " 条日志，推送线程 " + Thread.currentThread().getName()));
                LockSupport.parkNanos(300_000_000L);
            }
            for (int i = 0; i < 2; i++) {
                LockSupport.parkNanos(heartbeat.toMillis() * 1_000_000L);
                emitter.send(SseEmitter.event().comment("heartbeat"));
            }
            emitter.send(SseEmitter.event().name("run.status").data("SUCCEEDED"));
            emitter.complete();
        } catch (IOException | IllegalStateException disconnected) {
            emitter.completeWithError(disconnected);
        }
    }

    /** 回调里唯一要做的事：把引用摘掉，否则对象永远不释放。 */
    private static void drop(SseEmitter emitter, String reason) {
        SUBSCRIBERS.remove(emitter);
        System.out.println("[订阅者] " + reason + "，剩余 " + SUBSCRIBERS.size());
    }

    @GetMapping(value = "/download", produces = MediaType.ALL_VALUE)
    public ResponseEntity<StreamingResponseBody> download() {
        var bytes = "第 0 行轨迹\n第 1 行轨迹\n第 2 行轨迹\n".getBytes(StandardCharsets.UTF_8);
        StreamingResponseBody body = output -> { output.write(bytes); output.flush(); };
        return ResponseEntity.ok()
                .contentType(mediaType("text/plain;charset=UTF-8"))
                .contentLength(bytes.length)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("轨迹示例.txt", StandardCharsets.UTF_8).build().toString())
                .body(body);
    }

    /** 未知或非法的类型兜底成二进制，避免浏览器按错误类型渲染。 */
    private static MediaType mediaType(String value) {
        if (value == null || value.isBlank()) return MediaType.APPLICATION_OCTET_STREAM;
        try { return MediaType.parseMediaType(value); } catch (InvalidMediaTypeException invalid) { return MediaType.APPLICATION_OCTET_STREAM; }
    }
}
