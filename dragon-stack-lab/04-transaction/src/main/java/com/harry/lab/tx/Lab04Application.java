package com.harry.lab.tx;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 用六次转账对比六种结果：声明式回滚、自调用失效、受检异常不回滚、显式回滚、
 * 编程式主动回滚、编程式提交后回调。
 *
 * <p>【怎么看输出】第一，每一步之后都打印 A 与 B 的余额，任何一步该回滚却让余额变化了，
 * 就说明事务没生效。第二，第 2 步用的是同类内部自调用，余额会变，这是负面对照。
 * 第三，第 5 步金额 150 超过限额，没有异常，靠 setRollbackOnly 主动回滚；
 * 第 6 步金额 50 正常提交，会看到提交后回调的打印。
 *
 * <p>【为什么不用 CommandLineRunner】生产唯一使用的启动钩子是 @Bean ApplicationRunner，
 * 这里直接在 main 里取 Bean 并顺序执行，既短又不引入额外的启动钩子。
 */
@SpringBootApplication
public class Lab04Application {

    /** 每步执行完立刻打印余额，读者不需要对照日志时间戳就能看出哪一步被回滚。 */
    public static void main(String[] args) {
        var a = SpringApplication.run(Lab04Application.class, args).getBean(AccountService.class);
        System.out.println("[初始] " + line(a));
        step(a, "声明式 + 运行时异常（应回滚）", () -> a.transfer("A", "B", 150));
        step(a, "同类自调用（注解失效，已提交）", () -> a.transferWrong("A", "B", 150));
        step(a, "受检异常 + 不写 rollbackFor（已提交）", () -> a.transferChecked("A", "B", 150));
        step(a, "受检异常 + rollbackFor（应回滚）", () -> a.transferCheckedRollback("A", "B", 150));
        step(a, "编程式 + setRollbackOnly（应回滚）", () -> a.transferProgrammatic("A", "B", 150));
        step(a, "编程式 + 正常提交（触发提交后回调）", () -> a.transferProgrammatic("A", "B", 50));
    }

    /** 执行一步，捕获异常并打印，任何一步抛异常都不会让后面的演示中断。 */
    private static void step(AccountService a, String stage, Call call) {
        try { call.run(); } catch (Exception e) { System.out.println("捕获: " + e.getMessage()); }
        System.out.println("[" + stage + "] " + line(a));
    }

    private static String line(AccountService a) { return "A=" + a.balance("A") + " B=" + a.balance("B"); }

    /** 允许抛受检异常的调用接口，这样 step 能同时接住 IOException 与运行时异常。 */
    @FunctionalInterface interface Call { void run() throws Exception; }
}
