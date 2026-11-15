package com.harry.lab.config;

import java.nio.file.Path;
import org.springframework.core.convert.converter.Converter;

/**
 * 演示二：自定义配置类型转换器。
 *
 * <p>【这是什么】一个把 String 转成 java.nio.file.Path 的转换器，专供 @ConfigurationProperties
 * 绑定使用。它不属于 MVC 的全局类型转换，也不会影响请求参数解析。
 *
 * <p>【为什么需要它】第一，配置里的 data-root 写的是「相对当前进程工作目录」的路径，
 * 例如 ../data/runner，它指向宿主机文件系统，不是 classpath。第二，生产
 * harness-runner-core/config/FileSystemPathConverter.java 的 convert 方法只有一句
 * Path.of(source)，不做绝对化、不做 normalize。原因是一旦转成绝对路径，配置的语义就变了：
 * 同一个配置在不同工作目录下启动会指向不同位置，而「相对哪个目录」本身就是配置的一部分。
 * 第三，生产测试 FileSystemPathConverterTests 明确断言「保持相对、isAbsolute 与输入一致」，
 * 所以这里也不能补 toAbsolutePath().normalize()。
 *
 * <p>【语法怎么写】实现 Spring 的 Converter&lt;S, T&gt; 接口，在 convert 里完成一次转换。
 * 类声明为 final，表示它只是一段无状态的转换逻辑，不需要被继承。
 *
 * <p>【为什么不是 @Component】第一，配置绑定发生在容器启动的很早期，转换器必须在那之前
 * 就能被取到，走组件扫描会有时序风险。第二，类上标 @ConfigurationPropertiesBinding 并不会
 * 让它自动注册，这个注解没有注册能力；生产虽然也在类上标了一份，但真正让它进容器的是
 * 自动配置类里的 static @Bean 方法。
 *
 * <p>【@ConfigurationPropertiesBinding 到底做什么】它是一个 qualifier。配置绑定会构造一个
 * 专用的 ConversionService，只收集带这个 qualifier 的转换器，这样你的转换器不会污染
 * MVC 的全局转换。static 则保证 @Bean 方法在配置类实例化之前就能被解析，绑定阶段稳定拿到它。
 *
 * <p>【怎么被注册】见 Lab02Config。
 */
public final class FileSystemPathConverter implements Converter<String, Path> {

    @Override
    public Path convert(String source) {
        return Path.of(source);
    }
}
