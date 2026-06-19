package com.sstlfsj.rule.web.admin;

import com.sstlfsj.rule.job.api.dto.JobDefinitionDto;
import com.sstlfsj.rule.job.api.dto.JobExecutionVO;
import com.sstlfsj.rule.job.api.service.JobService;
import com.sstlfsj.rule.web.common.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** JobController 单元测试（管理类端点；Job 定义走 @TriggerTask 注解，无创建接口）。 */
class JobControllerTest {

    private MockMvc mockMvc;
    private JobService jobService;

    @BeforeEach
    void setUp() {
        jobService = mock(JobService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new JobController(jobService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private JobDefinitionDto jobDto() {
        return new JobDefinitionDto(5L, 1L, "fraud", "j1", "Job1", "0 0 0 * * *",
                new com.sstlfsj.rule.job.api.BeanMethodQuery("demo#subjects"), "login", "ACTIVE");
    }

    private JobExecutionVO execVO() {
        return new JobExecutionVO(9L, 5L, 1L, LocalDateTime.now(), "SUCCESS",
                2, 2, 0, null, LocalDateTime.now());
    }

    @Test
    void listJobsReturns200() throws Exception {
        when(jobService.listJobs(1L)).thenReturn(List.of(jobDto()));

        mockMvc.perform(get("/admin/v1/jobs").param("tenantId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].code").value("j1"))
                .andExpect(jsonPath("$.data[0].status").value("ACTIVE"));

        verify(jobService).listJobs(1L);
    }

    @Test
    void getJobReturns200() throws Exception {
        when(jobService.getJob(1L, 5L)).thenReturn(jobDto());

        mockMvc.perform(get("/admin/v1/jobs/5").param("tenantId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sceneCode").value("fraud"))
                .andExpect(jsonPath("$.data.subjectQuery.type").value("BEAN_METHOD"))
                .andExpect(jsonPath("$.data.subjectQuery.ref").value("demo#subjects"));
    }

    @Test
    void enableJobReturns200() throws Exception {
        doNothing().when(jobService).enableJob(1L, 5L);

        mockMvc.perform(post("/admin/v1/jobs/5/enable").param("tenantId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(jobService).enableJob(1L, 5L);
    }

    @Test
    void disableJobReturns200() throws Exception {
        doNothing().when(jobService).disableJob(1L, 5L);

        mockMvc.perform(post("/admin/v1/jobs/5/disable").param("tenantId", "1"))
                .andExpect(status().isOk());

        verify(jobService).disableJob(1L, 5L);
    }

    @Test
    void triggerJobReturns200WithExecution() throws Exception {
        when(jobService.triggerOnce(1L, 5L)).thenReturn(execVO());

        mockMvc.perform(post("/admin/v1/jobs/5/trigger").param("tenantId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.successCount").value(2));
    }

    @Test
    void recentExecutionsReturns200() throws Exception {
        when(jobService.recentExecutions(1L, 5L, 20)).thenReturn(List.of(execVO()));

        mockMvc.perform(get("/admin/v1/jobs/5/executions").param("tenantId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("SUCCESS"));

        verify(jobService).recentExecutions(1L, 5L, 20);
    }
}
