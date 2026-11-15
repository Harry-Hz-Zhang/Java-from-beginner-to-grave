package com.harry.lab.external;

/**
 * 领域异常：把「状态码」翻译成「调用方需要做什么决定」。
 *
 * <p>【第一，为什么不用 IllegalStateException】上层需要按原因分流：鉴权失败要提示
 * 用户检查账号密码，仓库不存在要提示换镜像地址，服务不可用才值得重试。
 * 只抛一个带字符串的通用异常，上层只能去解析 message，这是最脆弱的耦合。
 * 生产项目的 ImageRegistryException 同样带一个 Reason 枚举。
 *
 * <p>【第二，为什么只保留三个原因】它们对应三种完全不同的处置方式。
 * 其余情况（例如响应格式不对）在演示里归到 UNAVAILABLE，生产项目会再细分一个
 * INVALID_RESPONSE。枚举值不是越多越好，多一个就要多一处 switch 分支。
 *
 * <p>【第三，异常里不放敏感信息】message 只写人类可读的结论，不拼接请求头、
 * 不拼接 token、不拼接完整响应体。生产项目的类注释里专门写明了这一点，
 * 因为这类异常最终会进日志、进接口响应。
 */
public class RegistryException extends RuntimeException {

    public enum Reason {
        AUTHENTICATION_FAILED,
        REPOSITORY_NOT_FOUND,
        UNAVAILABLE
    }

    private final Reason reason;

    public RegistryException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public RegistryException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
