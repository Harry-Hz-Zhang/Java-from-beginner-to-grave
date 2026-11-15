package com.harry.lab.ioc;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 演示四：@Bean 与 @Component 的分工，以及 @Bean(name = ...) 给同类型对象命名。
 *
 * <p>【一句话区分】第一，@Component 加在「自己能改源码」的类上，由扫描器发现；
 * 第二，@Bean 写在配置类的方法上，用于注册「自己改不了源码」的类。
 * 本模块用它注册两个自己写的 Client，生产用它注册 OSS 客户端、MyBatis 拦截器这类第三方对象。
 *
 * <p>【为什么显式关闭 CGLIB 代理】@Configuration(proxyBeanMethods = false) 表示不需要
 * 代理配置类。默认的 true 会让容器生成一个 CGLIB 子类，用来保证配置类内部多次调用同一个
 * @Bean 方法时拿到同一个实例。本模块两个 @Bean 方法互不调用，这层代理没有作用，
 * 关掉可以少一层间接调用，启动也略快。
 *
 * <p>【生产是怎么做的】harness_saas 的三个配置类全部显式写了 proxyBeanMethods = false：
 * config/TaskConfiguration.java、config/RunnerImageConfiguration.java、
 * config/OssConfiguration.java。原因是它们的 @Bean 方法之间同样不互相调用。
 * 只有在配置类里直接调用另一个 @Bean 方法时，才需要保留默认的 true。
 *
 * <p>【@Bean(name = ...) 的语法】第一，方法名默认就是 Bean 名，transferClient() 产出的 Bean
 * 叫 transferClient。第二，显式写 name 让名字与语义脱钩，也方便注入处引用。
 * 两个方法返回类型相同，容器里就有两个同类型 Bean，注入时必须用 @Qualifier 点名。
 *
 * <p>【生产对照】config/OssConfiguration.java:23-31 用
 * {@code @Bean(name = "ossTransferClient", destroyMethod = "shutdown")} 与
 * {@code @Bean(name = "ossSigningClient", destroyMethod = "shutdown")} 注册两个 OSS 客户端：
 * 一个走内网端点传文件，一个走公网端点签发 URL。destroyMethod 让容器关闭时自动调用客户端
 * 的 shutdown 方法。本模块为了行数没有演示 destroyMethod，但它属于同一类需求：
 * 第三方对象的创建与销毁都交给容器管。
 */
@Configuration(proxyBeanMethods = false)
public class LabConfig {

    /** 上传通道。方法名即 Bean 名，这里再写一次 name 是为了让名字更贴近业务语义。 */
    @Bean(name = "transferClient") Client transferClient() { return new Client("transfer"); }

    /** 签名通道。与上一个方法返回同一类型，所以注入处必须用 @Qualifier 区分。 */
    @Bean(name = "signingClient") Client signingClient() { return new Client("signing"); }
}

/** 只携带一个用于观察的名字，用 record 表达不可变。包级可见，与配置类同文件。 */
record Client(String purpose) { }
