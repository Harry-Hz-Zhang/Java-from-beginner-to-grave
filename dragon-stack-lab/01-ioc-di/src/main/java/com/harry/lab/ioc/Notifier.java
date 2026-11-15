package com.harry.lab.ioc;

import org.springframework.stereotype.Component;

/**
 * 演示二：同一接口有多个实现时，容器怎么决定注入哪一个。
 *
 * <p>【为什么三个类型写在一个文件里】第一，这个模块的代码总量要压在七十行以内，拆成三个文件
 * 会多出三份 package 与 import。第二，生产代码确实是一个类一个文件，这里只是教学取舍。
 * 三个类型都是包级可见，Spring 的组件扫描并不要求类是 public。
 *
 * <p>【@Component 与 @Service 的区别】第一，功能上没有区别，@Service 的源码里把 @Component
 * 作为元注解，二者等价。第二，@Service 的价值是让读代码的人一眼看出这是业务层。
 * 真正有语义差异的是 @Controller（返回值会被 MVC 处理）和 @Repository（把数据库异常
 * 翻译成 Spring 的统一异常体系）。
 *
 * <p>【@Primary 在生产主代码里零处】很多教程把 @Primary 当成多实现的默认解法，
 * 但生产项目的 src/main 下一个 @Primary 也没有。它只出现在测试里，用途是替换真实 Bean：
 * harness_saas 的 RunnerImageIntegrationTests.java:72-85 用 @Bean @Primary 覆盖
 * OssStorage 与镜像仓库客户端，让集成测试不连真实 OSS。
 *
 * <p>【生产主代码的消歧方式】第一，两个同类型 Bean 用 @Bean(name = "...") 分别命名；
 * 第二，注入处在构造器参数上写 @Qualifier 精确指定。对照
 * config/OssConfiguration.java:23-31 的两个 OSS 客户端，与
 * infrastructure/storage/AliyunOssStorage.java:37-38 的构造器。
 * 本模块的 Client 两个 Bean 与 OrderService 的构造器就是照这个写法复刻的。
 *
 * <p>【这里为什么还留两个实现】第一，保留「按类型注入会歧义」这个事实，
 * 所以注入点必须写 @Qualifier("consoleNotifier")。第二，@Qualifier 既能选实现类，
 * 也能选 @Bean 方法产出的对象，两种来源的规则完全一致。
 */
public interface Notifier { void send(String message); }

/** 控制台通知。Bean 名默认是类名首字母小写，即 consoleNotifier，注入处按这个名字点名。 */
@Component class ConsoleNotifier implements Notifier {
    public void send(String message) { System.out.println("  [控制台通知] " + message); }
}

/** 审计留档。与 ConsoleNotifier 同类型，按类型注入会得到两个候选，必须靠 @Qualifier 消歧。 */
@Component class AuditNotifier implements Notifier {
    public void send(String message) { System.out.println("  [审计留档] " + message); }
}
