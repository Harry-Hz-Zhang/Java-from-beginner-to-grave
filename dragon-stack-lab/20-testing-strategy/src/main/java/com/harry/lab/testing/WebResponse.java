package com.harry.lab.testing;

/**
 * 统一响应信封，对应生产的 controller/vo/WebResponse。
 *
 * <p>【第一，为什么控制器不直接返回数据对象】接口的调用方需要区分
 * 「请求成功但结果为空」和「请求失败」，这两件事在裸数据上无法表达。
 * 加一层信封之后，`code` 承担业务语义，HTTP 状态码承担传输语义，两者互不干扰。
 *
 * <p>【第二，为什么用 record】信封构造之后不需要修改，record 天然不可变。
 * 静态工厂方法让调用点只写 WebResponse.success(data) 或 WebResponse.error(code, message)，
 * 不必每处都 new 三个参数。
 *
 * <p>【第三，断言时要解析外层】测试直接对字符串做 contains 匹配非常脆弱，
 * 字段顺序一变就挂。正确做法是解析成树，再按 code、data 逐层取值，
 * 这正是生产测试里 call() 辅助方法所做的事。
 */
public record WebResponse<T>(String code, String message, T data) {

    public static <T> WebResponse<T> success(T value) {
        return new WebResponse<>("OK", "成功", value);
    }

    public static <T> WebResponse<T> error(String code, String message) {
        return new WebResponse<>(code, message, null);
    }
}
