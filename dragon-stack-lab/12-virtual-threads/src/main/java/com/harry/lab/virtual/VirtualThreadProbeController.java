package com.harry.lab.virtual;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 演示三：验证 spring.threads.virtual.enabled 这个开关到底把请求跑在哪种线程上。
 *
 * <p>【这是什么】一个最小控制器，只做一件事：把处理本次请求的线程是不是虚拟线程报出来。
 * Thread.isVirtual() 是 Java 21 新增的判断方法，平台线程恒为 false。
 *
 * <p>【为什么需要它】配置项写在 yaml 里不等于真的生效，中间可能被别的容器工厂或环境变量覆盖。
 * 唯一可靠的验证方式是发一次请求看实际线程，这也是生产排查这类开关的标准动作。
 *
 * <p>【怎么用】application.yaml 里开关是 false 时启动一次、curl 一次，返回 virtual=false；
 * 改成 true 重启、再 curl 一次，返回 virtual=true，并且线程名变成 tomcat-handler 开头。
 */
@RestController
public class VirtualThreadProbeController {

    @GetMapping("/api/v1/thread")
    public Map<String, Object> thread() {
        return Map.of("virtual", Thread.currentThread().isVirtual(), "thread", Thread.currentThread().toString());
    }
}
