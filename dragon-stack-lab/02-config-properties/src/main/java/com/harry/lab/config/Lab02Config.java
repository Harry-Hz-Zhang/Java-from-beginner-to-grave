package com.harry.lab.config;

import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 演示三：把自定义转换器注册给配置绑定服务。
 *
 * <p>【三个修饰各自的作用】第一是 @Bean，转换器必须是容器里的对象，绑定过程才取得到它。
 * 第二是 @ConfigurationPropertiesBinding，它是一个 qualifier，把「配置绑定专用转换器」
 * 与「MVC 全局转换器」分开，绑定时只从这个小组里找。第三是 static，让这个 Bean 定义
 * 在配置类实例化之前就能被解析，绑定发生的极早期就能拿到实例。
 *
 * <p>【不要把它写成 @Component】第一，@ConfigurationPropertiesBinding 本身没有注册能力，
 * 标在类上不会让它变成 Bean，它只是给「已经是 Bean 的对象」贴一个分组标记。
 * 第二，如果靠组件扫描注册，时序就取决于扫描与绑定谁先完成，不稳定。
 * 生产的 FileSystemPathConverter 类上也标了这个注解，但那次标注是给读代码的人看的，
 * 真正让它进入容器的是下面这个 static @Bean 方法。
 *
 * <p>【生产对照】HarnessRunnerCoreAutoConfiguration 里的
 * {@code @Bean @ConfigurationPropertiesBinding static FileSystemPathConverter harnessRunnerPathConverter()}。
 * 三个修饰一起用不是随手写的：@Bean 负责注册，qualifier 负责分组，static 负责时序。
 */
@Configuration
public class Lab02Config {

    @Bean
    @ConfigurationPropertiesBinding
    static FileSystemPathConverter fileSystemPathConverter() {
        return new FileSystemPathConverter();
    }
}
