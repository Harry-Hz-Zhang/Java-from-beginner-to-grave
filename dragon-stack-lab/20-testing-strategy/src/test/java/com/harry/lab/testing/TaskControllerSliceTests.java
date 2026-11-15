package com.harry.lab.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * 第二组：切片测试，作为更轻的替代方案保留。
 *
 * <p>【第一，它不是生产的写法】生产项目零处 @WebMvcTest。这一组留在模块里，
 * 是因为它确实更轻：只装配 Web 层，不加载服务、不连数据库，通常一两秒就绪。
 * 适合在本地快速验证「路由和参数绑定有没有写对」。
 *
 * <p>【第二，切片要求把依赖显式替换掉】@WebMvcTest(TaskController.class) 只注册
 * 指定的控制器，不扫描 @Component，所以 TaskStore 不存在。这里必须用
 * @MockitoBean 补一个，否则上下文启动就报「找不到 Bean」。
 *
 * <p>【第三，它测不到什么】过滤器的真实顺序、Tomcat 的连接超时、请求体大小限制，
 * 以及任何依赖完整上下文的自动配置。这些恰好是生产项目用全量启动换回来的东西。
 * 一旦发现「切片过了、集成挂了」，就说明被测行为依赖了被切片裁掉的部分。
 */
@WebMvcTest(TaskController.class)
class TaskControllerSliceTests {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @MockitoBean
    private TaskStore store;

    @Test
    @DisplayName("切片模式下同样能验证 200 与信封内容")
    void returnsTaskWhenExists() throws Exception {
        when(store.find("7")).thenReturn(Optional.of(new TaskStore.TaskView("7", "任务-7")));

        var response = mvc.perform(get("/api/v1/tasks/7")).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(json.readTree(response.getContentAsString()).path("data").path("name").asString())
                .isEqualTo("任务-7");
    }

    @Test
    @DisplayName("校验失败仍然返回 400")
    void rejectsBlankName() throws Exception {
        var response = mvc.perform(post("/api/v1/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andReturn()
                .getResponse();

        assertThat(response.getStatus()).isEqualTo(400);
    }
}
