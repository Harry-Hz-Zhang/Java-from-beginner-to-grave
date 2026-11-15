package com.harry.lab.mapping;

import java.time.LocalDateTime;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 演示八：把一个 DO 依次转成 BO、VO，两跳都走一遍。
 *
 * <p>【第一，观察输出里少了什么】DO 上的六个审计字段没有出现在 BO 里，
 * BO 上的 id 没有出现在 VO 里。这就是分层的实际效果：
 * 每往上一层，就丢掉一批上一层才需要的字段。
 *
 * <p>【第二，为什么打印实现类名】TaskAdapter 和 TaskTransfer 都是只声明、不实现，
 * 但 getClass() 能打印出 Impl 结尾的类名，而这个类在你的源码里根本不存在。
 * 它是编译期生成的，和手写的类一样参与编译、一样跑成本地字节码。
 *
 * <p>【第三，生成的代码在哪】编译之后打开
 * target/generated-sources/annotations/com/harry/lab/mapping/ 目录，
 * 每个 mapper 对应一个 *Impl.java。那是理解 MapStruct 全部行为的最好材料，
 * 比任何文档都直接。
 *
 * <p>【第四，本模块不再演示「顺序写错就全 null」】那个说法不成立，
 * 真正必需的只有 lombok-mapstruct-binding 这一个桥接器，
 * 准确的结论写在 README 与 pom 的注释里。
 */
@SpringBootApplication
public class Lab19Application implements CommandLineRunner {

    private final TaskAdapter adapter;

    private final TaskTransfer transfer;

    public Lab19Application(TaskAdapter adapter, TaskTransfer transfer) {
        this.adapter = adapter;
        this.transfer = transfer;
    }

    @Override
    public void run(String... args) {
        var row = new TaskDO();
        row.setId(7L);
        row.setTaskUuid("uuid-7");
        row.setTaskName("镜像同步");
        row.setTaskStatus("RUNNING");
        row.setCreatedAt(LocalDateTime.now());
        row.setCreatedBy(1L);
        row.setVersion(0L);
        row.setDeleted(false);

        TaskBO bo = adapter.toBo(row);
        TaskVO vo = transfer.toVo(bo);

        System.out.println("第二跳结果 = " + vo);
        System.out.println("第一跳实现 = " + adapter.getClass().getName());
        System.out.println("第二跳实现 = " + transfer.getClass().getName());
        System.out.println("反向映射   = " + adapter.toRow(bo));
    }

    public static void main(String[] args) {
        SpringApplication.run(Lab19Application.class, args);
    }
}
