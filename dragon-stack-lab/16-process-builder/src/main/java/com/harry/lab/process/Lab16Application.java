package com.harry.lab.process;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import tools.jackson.databind.json.JsonMapper;

/**
 * 五条路径各验证一次：正常解析、Docker 不可用、操作冲突、目标缺失、超时，
 * 最后再演示一次长驻进程的优雅终止。
 *
 * <p>【为什么用 sh -c 模拟 docker】真调 docker 需要本机装了 Docker 且当前用户有权限，
 * 那会让这个 demo 从「学 ProcessBuilder」变成「学 Docker 权限配置」。
 * 命令字符串的结构和生产的 docker 命令完全一样，只是第一个元素换了。
 *
 * <p>【为什么缺失分支要单独打】它和失败分支的退出码都是 1，唯一区别在 stderr 内容。
 * 改动前两者都会被归成同一个异常，调用方无法分辨「容器被删了」和「Docker 挂了」。
 *
 * <p>【Jackson 3 的读法】JsonMapper.builder().build() 建实例，readTree 得到 JsonNode，
 * get("Id").asText() 取字符串。包名是 tools.jackson，不是老文章里的 com.fasterxml.jackson。
 */
@SpringBootApplication
public class Lab16Application implements CommandLineRunner {

    private final CommandRunner runner;
    private final JsonMapper json = JsonMapper.builder().build();

    public Lab16Application(CommandRunner runner) {
        this.runner = runner;
    }

    @Override
    public void run(String... args) throws Exception {
        var ok = runner.run(List.of("sh", "-c",
                "echo '{\"Id\":\"abc123\",\"State\":{\"Status\":\"running\"}}'"), Duration.ofSeconds(2));
        var node = json.readTree(ok.stdout());
        System.out.println("容器 Id   = " + node.get("Id").asText());
        System.out.println("容器状态  = " + node.get("State").get("Status").asText());

        expectFailure("失败分支", "echo 'Cannot connect to the Docker daemon' >&2; exit 1");
        expectFailure("冲突分支", "echo 'Conflict. container is running' >&2; exit 1");
        expectFailure("缺失分支", "echo 'Error: No such container: abc123' >&2; exit 1");
        expectFailure("超时分支", "sleep 10");

        var longLived = runner.start(List.of("sh", "-c", "while true; do echo tick; sleep 1; done"));
        System.out.println("长驻进程  = " + runner.terminate(longLived));
    }

    private void expectFailure(String label, String script) throws Exception {
        try {
            runner.run(List.of("sh", "-c", script), Duration.ofSeconds(2));
        } catch (CommandRunner.CommandException ex) {
            System.out.println(label + "  = [" + ex.code() + "] " + ex.getMessage());
        }
    }

    public static void main(String[] args) {
        SpringApplication.run(Lab16Application.class, args);
    }
}
