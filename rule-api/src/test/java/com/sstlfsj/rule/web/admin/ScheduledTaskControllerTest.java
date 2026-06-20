package com.sstlfsj.rule.web.admin;

import com.sstlfsj.rule.job.api.dto.CreateScheduledTaskRequest;
import com.sstlfsj.rule.job.api.dto.ScheduledTaskExecutionVO;
import com.sstlfsj.rule.job.api.dto.ScheduledTaskVO;
import com.sstlfsj.rule.job.api.service.ScheduledTaskService;
import com.sstlfsj.rule.web.common.ApiResponse;
import com.sstlfsj.rule.web.common.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** ScheduledTaskController 单元测试（管理类端点；任务定义走 @TriggerTask 注解，无创建接口）。 */
class ScheduledTaskControllerTest {

    private MockMvc mockMvc;
    private ScheduledTaskService taskService;

    @BeforeEach
    void setUp() {
        taskService = mock(ScheduledTaskService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ScheduledTaskController(taskService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private ScheduledTaskVO taskVO() {
        // 去中心化:taskType 为开放 string,config 为解析后的 JSON 对象(Map)
        return new ScheduledTaskVO(5L, 1L, "t1", "Task1", "TRIGGER", "0 0 0 * * *",
                Map.of("sceneCode", "fraud", "eventType", "login"),
                "ACTIVE", Instant.now(), Instant.now());
    }

    private ScheduledTaskExecutionVO execVO() {
        return new ScheduledTaskExecutionVO(9L, 5L, "SUCCESS", 2, 2, 0, null,
                Instant.now(), Instant.now());
    }

    @Test
    void listReturns200() throws Exception {
        when(taskService.list(1L)).thenReturn(List.of(taskVO()));

        mockMvc.perform(get("/admin/v1/scheduled-tasks").param("tenantId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].code").value("t1"))
                .andExpect(jsonPath("$.data[0].status").value("ACTIVE"));

        verify(taskService).list(1L);
    }

    @Test
    void getReturns200() throws Exception {
        when(taskService.get(1L, 5L)).thenReturn(taskVO());

        mockMvc.perform(get("/admin/v1/scheduled-tasks/5").param("tenantId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskType").value("TRIGGER"))
                .andExpect(jsonPath("$.data.config.sceneCode").value("fraud"))
                .andExpect(jsonPath("$.data.config.eventType").value("login"));
    }

    @Test
    void enableReturns200() throws Exception {
        doNothing().when(taskService).enable(1L, 5L);

        mockMvc.perform(post("/admin/v1/scheduled-tasks/5/enable").param("tenantId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(taskService).enable(1L, 5L);
    }

    @Test
    void disableReturns200() throws Exception {
        doNothing().when(taskService).disable(1L, 5L);

        mockMvc.perform(post("/admin/v1/scheduled-tasks/5/disable").param("tenantId", "1"))
                .andExpect(status().isOk());

        verify(taskService).disable(1L, 5L);
    }

    @Test
    void triggerReturns200WithExecution() throws Exception {
        when(taskService.triggerOnce(1L, 5L)).thenReturn(execVO());

        mockMvc.perform(post("/admin/v1/scheduled-tasks/5/trigger").param("tenantId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.successCount").value(2));
    }

    @Test
    void recentExecutionsReturns200() throws Exception {
        when(taskService.recentExecutions(1L, 5L, 20)).thenReturn(List.of(execVO()));

        mockMvc.perform(get("/admin/v1/scheduled-tasks/5/executions").param("tenantId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("SUCCESS"));

        verify(taskService).recentExecutions(1L, 5L, 20);
    }

    @Test
    void deleteReturns200() throws Exception {
        doNothing().when(taskService).delete(1L, 5L);

        mockMvc.perform(delete("/admin/v1/scheduled-tasks/5").param("tenantId", "1"))
                .andExpect(status().isOk());

        verify(taskService).delete(1L, 5L);
    }

    @Test
    void create_returns201AndVO() {
        ScheduledTaskVO vo = new ScheduledTaskVO(10L, 1L, "ingest", "测试", "OUTCOME_INGESTION",
                "0 0 2 * * *", null, "ACTIVE", null, null);
        when(taskService.create(any())).thenReturn(vo);

        ApiResponse<ScheduledTaskVO> resp = new ScheduledTaskController(taskService).create(
                new CreateScheduledTaskRequest(1L, "ingest", "测试", "0 0 2 * * *", "biz", "SELECT 1"));

        assertTrue(resp.success());
        assertEquals("ingest", resp.data().code());
        assertEquals("OUTCOME_INGESTION", resp.data().taskType());
    }
}
