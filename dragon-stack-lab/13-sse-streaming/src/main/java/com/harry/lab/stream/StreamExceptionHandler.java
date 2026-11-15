package com.harry.lab.stream;

import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

/**
 * 演示三：客户端提前断开之后，再往响应里写会抛 AsyncRequestNotUsableException。
 *
 * <p>【这是什么】全局异常处理器里的一条空处理。注解 @RestControllerAdvice 让容器把
 * @ExceptionHandler 声明的方法当作所有控制器共用的异常出口。
 *
 * <p>【为什么需要它】SSE 连接断开时，推送线程手里的 emitter 已经不可写，下一次 send
 * 就会抛这个异常。如果没人接住，它会一路冒到容器的异常处理里，每个断开的连接刷一屏堆栈，
 * 日志被噪音淹没。接住之后什么都不做，因为此时响应早已提交，没有任何东西可以再写回去。
 *
 * <p>【生产怎么写】controller/GlobalExceptionHandler.java 第 76 到 78 行就是一个方法体
 * 为空的 handler。注意它没有「响应是否已提交」的判断，也不需要判断：
 * 这个异常只会发生在响应不可用之后，能进到这里就说明已经没救了。
 */
@RestControllerAdvice
public class StreamExceptionHandler {

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    void disconnected() {}
}
