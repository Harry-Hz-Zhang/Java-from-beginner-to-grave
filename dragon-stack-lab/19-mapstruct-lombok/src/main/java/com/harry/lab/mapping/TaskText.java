package com.harry.lab.mapping;

import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * 演示五：供 expression 调用的辅助 Bean。
 *
 * <p>【第一，expression 能调到的不只是静态方法】表达式里写的是一个普通 Java 表达式，
 * mapper 里能访问到什么，它就能用什么。抽象类 mapper 可以用 @Autowired 注入 Bean，
 * 注入进来的对象在 expression 里可以直接按字段名调用，包括有状态的对象。
 * 生产项目的 TaskAdapter 就是这样调用解密器的，写法是
 * expression = "java(cipher.decrypt(row.getMcpServers(), row.getTaskUuid()))"。
 *
 * <p>【第二，为什么逻辑不写在 expression 里】expression 是一段字符串，编译器不检查，
 * 写错了只能在生成代码时报错，而且很难看。所以只要超过一个方法调用，
 * 就应该挪到这个辅助类里：IDE 能跳转、能重构、能单独写单元测试。
 *
 * <p>【第三，本 demo 用的是纯函数】真实项目里这里可能是解密、JSON 反序列化、
 * 逗号分隔字符串转 List。为了不引入加密库，这里只做一次判空、拼接和小写转换，
 * 但对 MapStruct 来说完全等价：它只关心「有一个方法可以被调用」。
 */
@Component
public class TaskText {

    /** 把名称和状态拼成展示文案，空名称时退化为状态本身。 */
    public String label(String name, String status) {
        String text = status == null ? "" : status.toLowerCase(Locale.ROOT);
        return name == null || name.isBlank() ? text : name + " / " + text;
    }
}
