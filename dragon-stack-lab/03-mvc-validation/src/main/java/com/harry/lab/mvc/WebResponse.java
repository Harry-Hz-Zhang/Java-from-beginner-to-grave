package com.harry.lab.mvc;

/**
 * 演示四：统一的响应外壳。
 *
 * <p>【这是什么】一个泛型 record，把成功与失败都收敛成同样的三个字段：code、message、data。
 * 生产 controller/vo/WebResponse.java:3-10 就是这三个字段加两个静态工厂。
 *
 * <p>【语法怎么写】record 自动生成构造器与访问器；静态工厂负责屏蔽构造细节，
 * 调用方只写 WebResponse.success(...) 或 WebResponse.error(...)，不会忘记字段顺序。
 *
 * <p>【为什么这里需要】第一，前端只需写一次解析逻辑，成功失败都从 data 或 message 取值。
 * 第二，HTTP 状态码交给 ResponseEntity 表达，业务结果用 code 表达，两者不混在一个字段里。
 *
 * <p>【生产在哪用】controller 包下所有普通接口都返回 WebResponse，
 * 只有 SSE 流式接口返回 ResponseEntity&lt;SseEmitter&gt;，因为要把响应体当作事件流持续写入。
 */
public record WebResponse<T>(String code, String message, T data) {

    /** 成功。code 固定为 OK，与生产保持一致。 */
    public static <T> WebResponse<T> success(T value) { return new WebResponse<>("OK", "成功", value); }

    /** 失败。data 置空，错误码取 ErrorCode 的名字，message 是给人看的说明。 */
    public static WebResponse<Void> error(String code, String message) { return new WebResponse<>(code, message, null); }
}
