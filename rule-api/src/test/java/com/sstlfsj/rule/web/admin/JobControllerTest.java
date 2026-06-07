package com.sstlfsj.rule.web.admin;

import com.sstlfsj.rule.job.api.dto.JobDefinitionDto;
import com.sstlfsj.rule.job.api.dto.JobExecutionVO;
import com.sstlfsj.rule.job.api.service.JobService;
import com.sstlfsj.rule.web.common.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** JobController 单元测试。 */
class JobControllerTest {

    private MockMvc mockMvc;
    private JobService jobService;

    @BeforeEach
    void setUp() {
        jobService = mock(JobService.class);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new JobController(jobService, JsonMapper.builder().build()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    private JobDefinitionDto jobDto() {
        return new JobDefinitionDto(5L, "1", "fraud", "j1", "Job1", "0 0 0 * * *",
                "{\"type\":\"SQL\",\"sql\":\"SELECT 1 AS subjectId\"}", "login", null, "ACTIVE");
    }

    private JobExecutionVO execVO() {
        return new JobExecutionVO(9L, 5L, "1", LocalDateTime.now(), "SUCCESS",
                2, 2, 0, null, LocalDateTime.now());
    }

    private static final String CREATE_BODY = """
            {"tenantId":"1","sceneCode":"fraud","code":"j1","name":"Job1",
             "cronExpression":"0 0 0 * * *",
             "subjectQuery":{"type":"SQL","sql":"SELECT 1 AS subjectId"},
             "eventType":"login"}
            """;

    @Test
    void createJobReturns200WithId() throws Exception {
        when(jobService.createJob(any())).thenReturn(7L);

        mockMvc.perform(post("/admin/v1/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Actor-Id", "user1")
                        .content(CREATE_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(7));

        verify(jobService).createJob(any());
    }

    @Test
    void createJobForPullSceneReturns400() throws Exception {
        when(jobService.createJob(any()))
                .thenThrow(new IllegalArgumentException("PULL Scene 不允许绑定 Job: fraud"));

        mockMvc.perform(post("/admin/v1/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Actor-Id", "user1")
                        .content(CREATE_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("INVALID_ARGUMENT"));
    }

    @Test
    void createJobMissingTenantIdReturns400() throws Exception {
        mockMvc.perform(post("/admin/v1/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Actor-Id", "user1")
                        .content("""
                            {"sceneCode":"fraud","code":"j1","name":"Job1",
                             "cronExpression":"0 0 0 * * *",
                             "subjectQuery":{"type":"SQL"},"eventType":"login"}
                            """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listJobsReturns200() throws Exception {
        when(jobService.listJobs("1")).thenReturn(List.of(jobDto()));

        mockMvc.perform(get("/admin/v1/jobs").param("tenantId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].code").value("j1"))
                .andExpect(jsonPath("$.data[0].status").value("ACTIVE"));

        verify(jobService).listJobs("1");
    }

    @Test
    void getJobReturns200() throws Exception {
        when(jobService.getJob("1", 5L)).thenReturn(jobDto());

        mockMvc.perform(get("/admin/v1/jobs/5").param("tenantId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sceneCode").value("fraud"));
    }

    @Test
    void enableJobReturns200() throws Exception {
        doNothing().when(jobService).enableJob("1", 5L);

        mockMvc.perform(post("/admin/v1/jobs/5/enable").param("tenantId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(jobService).enableJob("1", 5L);
    }

    @Test
    void disableJobReturns200() throws Exception {
        doNothing().when(jobService).disableJob("1", 5L);

        mockMvc.perform(post("/admin/v1/jobs/5/disable").param("tenantId", "1"))
                .andExpect(status().isOk());

        verify(jobService).disableJob("1", 5L);
    }

    @Test
    void triggerJobReturns200WithExecution() throws Exception {
        when(jobService.triggerOnce("1", 5L)).thenReturn(execVO());

        mockMvc.perform(post("/admin/v1/jobs/5/trigger").param("tenantId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.successCount").value(2));
    }

    @Test
    void recentExecutionsReturns200() throws Exception {
        when(jobService.recentExecutions("1", 5L, 20)).thenReturn(List.of(execVO()));

        mockMvc.perform(get("/admin/v1/jobs/5/executions").param("tenantId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("SUCCESS"));

        verify(jobService).recentExecutions("1", 5L, 20);
    }
}
