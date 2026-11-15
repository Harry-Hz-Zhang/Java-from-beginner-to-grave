package com.harry.lab.schedule;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 观察三件事：定时任务按固定延迟反复执行且两个任务落在不同调度线程上、
 * 通过代理调用的异步方法线程名是 audit- 开头、通过 this 调用的那次仍是 main。
 *
 * <p>【这是什么】一个非 Web 的 Spring Boot 应用。CommandLineRunner 的 run 方法在容器
 * 启动完成后由主线程调用一次，用它触发异步方法、等定时任务跑几轮，然后主动退出。
 *
 * <p>【第一，为什么这里要 sleep】这不是 Web 应用，run 方法执行完容器就该关闭，
 * 不 sleep 的话定时任务一次都来不及跑。sleep 期间调度线程在跑，输出和主线程交错出现，
 * 这本身就是并发的直观证据。生产是 Web 应用，Tomcat 让进程常驻，不需要这一段。
 *
 * <p>【第二，为什么必须主动退出】@EnableScheduling 会创建一个非守护的调度线程池，
 * 它一直等下一次触发，JVM 就不会退出，你运行这个 demo 会看到程序一直挂着。
 * SpringApplication.exit 会关闭容器、停止调度器，再把退出码交给 System.exit。
 */
@SpringBootApplication
public class Lab11Application implements CommandLineRunner {

    private final ScheduledTasks tasks;

    public Lab11Application(ScheduledTasks tasks) {
        this.tasks = tasks;
    }

    @Override
    public void run(String... args) throws InterruptedException {
        tasks.writeAudit("走代理调用，应当在 audit- 线程上执行");
        tasks.triggerWrong("走 this 调用，应当在 main 线程上执行");
        Thread.sleep(6000);
        System.out.println("主线程结束，容器关闭时会把线程池里剩余任务跑完");
    }

    public static void main(String[] args) {
        var context = SpringApplication.run(Lab11Application.class, args);
        System.exit(SpringApplication.exit(context));
    }
}
