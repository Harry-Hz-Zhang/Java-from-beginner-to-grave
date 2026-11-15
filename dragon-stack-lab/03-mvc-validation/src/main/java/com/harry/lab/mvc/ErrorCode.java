package com.harry.lab.mvc;

/**
 * 演示五：错误码与业务异常。
 *
 * <p>【这是什么】枚举里每一项携带一个 HTTP 状态码，业务异常只持有枚举与一条说明。
 * 生产 domain/iam/ErrorCode.java:3-34 与 domain/iam/BusinessException.java:3-13 就是这个结构，
 * 只是错误码更多，例如 401、403、409、413、429、503 都有。
 *
 * <p>【为什么需要它】第一，错误码集中在一处，控制器和全局异常处理都不再散落魔法数字。
 * 第二，业务异常继承 RuntimeException，符合 @Transactional 默认只回滚运行时异常的规则。
 * 第三，业务上「没找到」用 BusinessException.notFound 表达，不用 NoSuchElementException，
 * 生产主代码里没有一处 NoSuchElementException。
 *
 * <p>【生产在哪用】领域层抛 BusinessException，全局异常处理按 code().httpStatus() 定状态码，
 * 按 code().name() 写进响应的 code 字段，两层职责清晰。
 */
public enum ErrorCode {
    INVALID_REQUEST(400), NOT_FOUND(404), VERSION_CONFLICT(409), INTERNAL_ERROR(500);

    private final int httpStatus;

    ErrorCode(int httpStatus) { this.httpStatus = httpStatus; }

    public int httpStatus() { return httpStatus; }
}

/**
 * 业务异常。包级可见即可，因为抛出与捕获都在同一个包内。
 * 静态工厂 notFound 让调用处读起来像业务语言，不必记住枚举常量。
 */
class BusinessException extends RuntimeException {

    private final ErrorCode code;

    BusinessException(ErrorCode code, String message) { super(message); this.code = code; }

    ErrorCode code() { return code; }

    static BusinessException notFound(String message) { return new BusinessException(ErrorCode.NOT_FOUND, message); }
}
