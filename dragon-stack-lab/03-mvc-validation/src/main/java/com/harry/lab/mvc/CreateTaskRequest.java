package com.harry.lab.mvc;

import jakarta.validation.constraints.*;

/**
 * 演示一：用一个 record 承接请求体，同时声明校验规则。
 *
 * <p>【参数校验的两步机制】这些注解只是元数据，不会自己执行。第一，方法参数上的 @Valid
 * 让 Spring 在调用业务方法之前把对象交给校验器过一遍。第二，不通过就抛
 * MethodArgumentNotValidException，业务方法根本不会执行。所以 @Valid 漏写，注解全是摆设。
 *
 * <p>【包装类型而不是基本类型】replicas 用 Integer 而不是 int。第一，请求体里不写这个字段时，
 * int 会被反序列化成 0，@Min(1) 就把「没传」和「传了 0」混为一谈。第二，Integer 可以是 null，
 * 配 @NotNull 才能明确表达「这个字段必填」。生产 controller/request 下的请求对象都遵循这条。
 *
 * <p>【注解语义】@NotBlank 判 null、空串与纯空格；@NotEmpty 只判 null 与长度零；@NotNull 只判 null。
 * @Size 管长度，@Min 管数值下界，@PositiveOrZero 管非负。写错会放过空格或负数。
 *
 * <p>【生产对照】harness_saas 的 controller/request 目录下每个请求对象都是这个套路，
 * 例如创建任务时的 prompt 长度限制、模型名非空校验。
 */
public record CreateTaskRequest(
        @NotBlank(message = "任务名不能为空") @Size(max = 32, message = "任务名最长 32 个字符") String name,
        @NotNull(message = "副本数不能为空") @PositiveOrZero(message = "副本数不能为负") Integer replicas) { }
