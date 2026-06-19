package com.sstlfsj.rule.job.internal.service;

import com.sstlfsj.rule.config.api.dto.SceneDetailDto;
import com.sstlfsj.rule.config.api.service.SceneService;
import com.sstlfsj.rule.job.api.BeanMethodQuery;
import com.sstlfsj.rule.job.api.TaskStatus;
import com.sstlfsj.rule.job.api.TaskType;
import com.sstlfsj.rule.job.api.TriggerConfig;
import com.sstlfsj.rule.job.api.dto.ScheduledTaskVO;
import com.sstlfsj.rule.job.internal.domain.ScheduledTask;
import com.sstlfsj.rule.job.internal.repository.ScheduledTaskExecutionMapper;
import com.sstlfsj.rule.job.internal.repository.ScheduledTaskMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScheduledTaskServiceImplTest {

    @Mock
    ScheduledTaskMapper taskMapper;
    @Mock
    ScheduledTaskExecutionMapper executionMapper;
    @Mock
    SceneService sceneService;
    @Mock
    ScheduledTaskScheduleManager scheduleManager;

    @InjectMocks
    ScheduledTaskServiceImpl service;

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
        t.setTaskType(TaskType.TRIGGER);
        t.setCron("0 0 0 * * *");
        t.setConfig(new TriggerConfig("s1", "login", new BeanMethodQuery("a#b")));
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
    void getReturnsTypedConfig() {
        ScheduledTask t = task(TaskStatus.ACTIVE);
        when(taskMapper.selectById(5L)).thenReturn(t);

        ScheduledTaskVO vo = service.get(1L, 5L);

        assertThat(vo.config()).isInstanceOf(TriggerConfig.class);
        assertThat(((TriggerConfig) vo.config()).sceneCode()).isEqualTo("s1");
        assertThat(vo.status()).isEqualTo("ACTIVE");
        assertThat(vo.taskType()).isEqualTo(TaskType.TRIGGER);
    }
}
