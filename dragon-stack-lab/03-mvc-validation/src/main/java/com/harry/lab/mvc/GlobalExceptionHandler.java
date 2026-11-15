package com.harry.lab.mvc;

import org.slf4j.*;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.*;

/**
 * 演示三：全局异常处理，把控制器抛出的异常收敛成统一结构。
 *
 * <p>【为什么必须有它】第一，没有它时校验失败返回 Spring 默认错误体，前端要写两套解析。
 * 第二，编程错误（空指针之类）会连堆栈路径一起暴露给调用方。加了它之后所有错误都是
 * WebResponse 结构，状态码由异常类型决定。
 *
 * <p>【@RestControllerAdvice 是什么】@ControllerAdvice 加 @ResponseBody。
 * 它用 AOP 把 @ExceptionHandler 方法织入所有控制器，可以用 basePackages 缩小作用范围。
 *
 * <p>【@ExceptionHandler 的数组写法】一个方法可以接多个异常类型，语义相同的错误合并处理，
 * 避免为每个类型复制一份方法体。生产 controller/GlobalExceptionHandler.java 就把
 * MethodArgumentNotValidException、HandlerMethodValidationException、HttpMessageNotReadableException、
 * MethodArgumentTypeMismatchException 放在同一个数组里，统一返回 INVALID_REQUEST。
 *
 * <p>【为什么必须单独处理 HandlerMethodValidationException】第一，@Valid 校验请求体时抛的是
 * MethodArgumentNotValidException。第二，@RequestParam 上的 @Min/@Max 属于方法参数级校验，
 * 抛的是 HandlerMethodValidationException，两者不是同一个类。第三，漏掉它不会变成 400，
 * 会落到兜底分支变成 500，前端看到的是「服务端错误」，排查方向就被带偏了。
 *
 * <p>【其他三个分支的作用】第一，HttpMessageNotReadableException 表示请求体不是合法 JSON，
 * 或者多了未知字段。第二，HttpRequestMethodNotSupportedException 与
 * HttpMediaTypeNotSupportedException 分别是 405 与 415，不能混进 400。第三，
 * MethodArgumentTypeMismatchException 表示路径参数类型不对，例如把 UUID 写成乱码。
 *
 * <p>【兜底分支】Exception.class 必须打日志再返回 500。第一，不打日志等于把现场丢了。
 * 第二，不把异常信息直接回给调用方，避免泄露内部细节。
 *
 * <p>【为什么返回 ResponseEntity 并显式设置 Content-Type】第一，状态码要按异常类型变化，
 * 不能只靠 @ResponseStatus 写死。第二，显式写 application/json 可以避免内容协商把错误体
 * 变成别的格式。第三，401 还要带 WWW-Authenticate: Bearer，这是 HTTP 规范要求。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 400：请求体校验、参数级校验、JSON 解析失败、路径参数类型不匹配，四类合成一个出口。 */
    @ExceptionHandler({MethodArgumentNotValidException.class, HandlerMethodValidationException.class, HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<WebResponse<Void>> invalid(Exception e) {
        return json(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST, "请求参数不合法");
    }

    /** 业务异常：状态码与错误码都从枚举取，新增错误码不用改这里。 */
    @ExceptionHandler(BusinessException.class)
    ResponseEntity<WebResponse<Void>> business(BusinessException e) {
        return json(HttpStatus.valueOf(e.code().httpStatus()), e.code(), e.getMessage());
    }

    /** 405 与 415 合成一个方法，靠异常类型区分状态码。 */
    @ExceptionHandler({HttpRequestMethodNotSupportedException.class, HttpMediaTypeNotSupportedException.class})
    ResponseEntity<WebResponse<Void>> protocol(Exception e) {
        var method = e instanceof HttpRequestMethodNotSupportedException;
        return json(method ? HttpStatus.METHOD_NOT_ALLOWED : HttpStatus.UNSUPPORTED_MEDIA_TYPE, ErrorCode.INVALID_REQUEST, method ? "不支持此 HTTP 方法" : "请使用 application/json");
    }

    /** 兜底：先记日志再回 500，异常原文不写进响应体。 */
    @ExceptionHandler(Exception.class)
    ResponseEntity<WebResponse<Void>> fallback(Exception e) {
        LOG.error("未处理异常", e);
        return json(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR, "服务内部错误");
    }

    /** 统一出口：状态码、错误码、说明都从这里写，避免每个分支重复设置响应头。 */
    private ResponseEntity<WebResponse<Void>> json(HttpStatus status, ErrorCode code, String message) {
        var builder = ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON);
        // 生产错误码里有 401，规范要求同时返回质询头，这里保留同一个出口
        return (status == HttpStatus.UNAUTHORIZED ? builder.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer") : builder).body(WebResponse.error(code.name(), message));
    }
}
