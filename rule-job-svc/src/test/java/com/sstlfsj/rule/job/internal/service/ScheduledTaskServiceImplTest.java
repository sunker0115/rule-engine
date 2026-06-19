package com.sstlfsj.rule.job.internal.service;

import com.sstlfsj.rule.config.api.dto.SceneDetailDto;
import com.sstlfsj.rule.config.api.service.SceneService;
import com.sstlfsj.rule.job.api.BeanMethodQuery;
import com.sstlfsj.rule.job.api.TaskStatus;
import com.sstlfsj.rule.job.api.TriggerConfig;
import com.sstlfsj.rule.job.api.dto.CreateScheduledTaskRequest;
import com.sstlfsj.rule.job.api.dto.ScheduledTaskVO;
import com.sstlfsj.rule.job.internal.domain.ScheduledTask;
import com.sstlfsj.rule.job.internal.repository.ScheduledTaskExecutionMapper;
import com.sstlfsj.rule.job.internal.repository.ScheduledTaskMapper;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScheduledTaskServiceImplTest {

    private final ScheduledTaskMapper taskMapper = mock(ScheduledTaskMapper.class);
    private final ScheduledTaskExecutionMapper executionMapper = mock(ScheduledTaskExecutionMapper.class);
    private final SceneService sceneService = mock(SceneService.class);
    private final ScheduledTaskScheduleManager scheduleManager = mock(ScheduledTaskScheduleManager.class);
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    private final ScheduledTaskServiceImpl service = new ScheduledTaskServiceImpl(
            taskMapper, executionMapper, sceneService, scheduleManager, objectMapper);

    private SceneDetailDto scene(String mode) {
        return new SceneDetailDto(1L, 1L, "s1", "name", null, mode, "USER",
                List.of(), List.of(), Map.of(), "ACTIVE");
    }

    private ScheduledTask task(TaskStatus status) {
        ScheduledTask t = new ScheduledTask();
        t.setId(5L);
        t.setTenantId(1L);
        t.setCode("t1");
        t.setName("Task1");
        t.setTaskType("TRIGGER");
        t.setCron("0 0 0 * * *");
        t.setConfig(objectMapper.writeValueAsString(
                new TriggerConfig("s1", "login", new BeanMethodQuery("a#b"))));
        t.setStatus(status);
        return t;
    }

    @Test
    void enableForPushSceneRegistersSchedule() {
        ScheduledTask t = task(TaskStatus.DISABLED);
        when(taskMapper.selectById(5L)).thenReturn(t);
        when(sceneService.getScene(1L, "s1")).thenReturn(scene("PUSH"));

        service.enable(1L, 5L);

        assertEquals(TaskStatus.ACTIVE, t.getStatus());
        verify(scheduleManager).register(t);
    }

    @Test
    void rejectsEnableForPullScene() {
        ScheduledTask t = task(TaskStatus.DISABLED);
        when(taskMapper.selectById(5L)).thenReturn(t);
        when(sceneService.getScene(1L, "s1")).thenReturn(scene("PULL"));

        assertThrows(IllegalArgumentException.class, () -> service.enable(1L, 5L));

        verify(scheduleManager, never()).register(any());
    }

    @Test
    void enableAllowedWhenSceneNotConfiguredInEngine() {
        // scene 不存在于引擎(getScene 抛 IllegalArgumentException)→ 跳过 PULL 校验,enable 成功
        ScheduledTask t = task(TaskStatus.DISABLED);
        when(taskMapper.selectById(5L)).thenReturn(t);
        when(sceneService.getScene(1L, "s1")).thenThrow(new IllegalArgumentException("Scene 不存在: s1"));

        service.enable(1L, 5L);

        assertEquals(TaskStatus.ACTIVE, t.getStatus());
        verify(scheduleManager).register(t);
    }

    @Test
    void disableUnregistersSchedule() {
        ScheduledTask t = task(TaskStatus.ACTIVE);
        when(taskMapper.selectById(5L)).thenReturn(t);

        service.disable(1L, 5L);

        assertEquals(TaskStatus.DISABLED, t.getStatus());
        verify(taskMapper).updateById(t);
        verify(scheduleManager).unregister(5L);
    }

    @Test
    void rejectsCrossTenantAccess() {
        ScheduledTask t = new ScheduledTask();
        t.setId(5L);
        t.setTenantId(999L);
        when(taskMapper.selectById(5L)).thenReturn(t);

        assertThrows(IllegalArgumentException.class, () -> service.get(1L, 5L));
    }

    @Test
    void rejectsUnknownTask() {
        when(taskMapper.selectById(5L)).thenReturn(null);

        assertThrows(IllegalArgumentException.class, () -> service.get(1L, 5L));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getReturnsParsedJsonConfig() {
        ScheduledTask t = task(TaskStatus.ACTIVE);
        when(taskMapper.selectById(5L)).thenReturn(t);

        ScheduledTaskVO vo = service.get(1L, 5L);

        // config 以解析后的 JSON 对象(Map)出口,框架核不持 typed 全集
        assertThat(vo.config()).isInstanceOf(Map.class);
        assertThat(((Map<String, Object>) vo.config()).get("sceneCode")).isEqualTo("s1");
        assertThat(vo.status()).isEqualTo("ACTIVE");
        assertThat(vo.taskType()).isEqualTo("TRIGGER");
    }

    @Test
    void create_insertsTaskAndRegisters() {
        when(taskMapper.findByTenantCode(1L, "ingest-test")).thenReturn(null);

        CreateScheduledTaskRequest req = new CreateScheduledTaskRequest(
                1L, "ingest-test", "测试回灌", "0 0 2 * * *", "biz",
                "SELECT event_id, outcome_label, outcome_value, labeled_at FROM biz_label WHERE tenant_id = :tenantId");
        ScheduledTaskVO vo = service.create(req);

        assertThat(vo.code()).isEqualTo("ingest-test");
        assertThat(vo.taskType()).isEqualTo("OUTCOME_INGESTION");
        verify(scheduleManager).register(any(ScheduledTask.class));
    }

    @Test
    void create_duplicateCode_throws() {
        ScheduledTask existing = new ScheduledTask();
        existing.setCode("ingest-dup");
        when(taskMapper.findByTenantCode(1L, "ingest-dup")).thenReturn(existing);

        CreateScheduledTaskRequest req = new CreateScheduledTaskRequest(
                1L, "ingest-dup", "重复", "0 0 2 * * *", "biz", "SELECT 1");
        assertThrows(IllegalArgumentException.class, () -> service.create(req));
    }
}
