package com.harry.lab.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * 第一组：生产项目实际使用的写法，全量启动加 MockMvc。
 *
 * <p>【第一，为什么不是 @WebMvcTest】生产项目零处使用切片测试。控制器契约测试一律
 * 写着 @SpringBootTest 加 @AutoConfigureMockMvc(print = MockMvcPrint.NONE)。
 * 全量启动换来的是「测的就是要上线的那个上下文」：Bean 装配、消息转换器、
 * 异常处理器、过滤器链全部真实生效，不会出现「切片里过了、线上不过」的情况。
 * 代价是启动慢，所以生产把它和 Testcontainers 放在一起，一个上下文跑多条用例。
 *
 * <p>【第二，print = NONE 是为了什么】@AutoConfigureMockMvc 默认会在失败时
 * 把请求和响应打印到控制台。几百条用例里失败的永远是少数，默认输出会把日志淹掉，
 * 真正有用的信息反而找不到。生产项目显式关掉它。
 *
 * <p>【第三，断言用 assertThat 加 Jackson 解析，而不是 jsonPath】
 * 生产的断言形式是 assertThat(response.getStatus()).isEqualTo(200)，
 * 再用 ObjectMapper 把响应体解析成树，按 data、code 逐层取值。
 * 这样断言的是解析后的值，不受字段顺序影响，也不依赖 MockMvc 的匹配器 API。
 *
 * <p>【第四，@MockitoSpyBean 与 @MockitoBean 的分工】spy 保留真实实现，
 * 只拦截被显式 stub 或需要 verify 的调用，用来观察「这个方法到底有没有被调到」；
 * mock 则完全替换实现。Boot 3.4 起旧的 @MockBean 已废弃，这两者是替代品。
 */
@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class TaskControllerWebTests {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @MockitoSpyBean
    private TaskStore store;

    @Test
    @DisplayName("查询存在的任务返回 200，信封 code 为 OK")
    void returnsTaskWhenExists() throws Exception {
        when(store.find("7")).thenReturn(java.util.Optional.of(new TaskStore.TaskView("7", "任务-7")));

        var response = mvc.perform(get("/api/v1/tasks/7")).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        var body = json.readTree(response.getContentAsString());
        assertThat(body.path("code").asString()).isEqualTo("OK");
        assertThat(body.path("data").path("name").asString()).isEqualTo("任务-7");
        verify(store).find("7");
    }

    @Test
    @DisplayName("查询不存在的任务返回 404 和业务码")
    void returnsNotFoundEnvelope() throws Exception {
        var response = mvc.perform(get("/api/v1/tasks/missing")).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(json.readTree(response.getContentAsString()).path("code").asString())
                .isEqualTo("TASK_NOT_FOUND");
    }

    @Test
    @DisplayName("名称为空时返回 400，说明校验生效")
    void rejectsBlankName() throws Exception {
        var response = mvc.perform(post("/api/v1/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andReturn()
                .getResponse();

        assertThat(response.getStatus()).isEqualTo(400);
    }
}
