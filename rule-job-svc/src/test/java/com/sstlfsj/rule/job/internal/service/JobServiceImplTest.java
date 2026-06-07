package com.sstlfsj.rule.job.internal.service;

import com.sstlfsj.rule.config.api.dto.SceneDetailDto;
import com.sstlfsj.rule.config.api.service.SceneService;
import com.sstlfsj.rule.job.api.dto.CreateJobCommand;
import com.sstlfsj.rule.job.internal.domain.JobDefinition;
import com.sstlfsj.rule.job.internal.repository.JobDefinitionMapper;
import com.sstlfsj.rule.job.internal.repository.JobExecutionMapper;
import com.sstlfsj.rule.job.internal.runner.JobRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JobServiceImplTest {

    @Mock
    JobDefinitionMapper jobMapper;
    @Mock
    JobExecutionMapper executionMapper;
    @Mock
    SceneService sceneService;
    @Mock
    JobScheduleManager scheduleManager;
    @Mock
    JobRunner jobRunner;

    @InjectMocks
    JobServiceImpl service;

    private SceneDetailDto scene(String mode) {
        return new SceneDetailDto(1L, "1", "s1", "name", null, mode, "USER",
                List.of(), List.of(), Map.of(), 1, "ACTIVE");
    }

    private CreateJobCommand cmd() {
        return new CreateJobCommand("1", "s1", "j1", "Job1", "* * * * * *",
                "{\"type\":\"SQL\",\"sql\":\"x\"}", "trade.completed", null, "actor");
    }

    @Test
    void createsJobForPushSceneAndRegistersSchedule() {
        when(sceneService.getScene("1", "s1")).thenReturn(scene("PUSH"));
        when(jobMapper.insert(any(JobDefinition.class))).thenAnswer(inv -> {
            ((JobDefinition) inv.getArgument(0)).setId(5L);
            return 1;
        });

        Long id = service.createJob(cmd());

        assertEquals(5L, id);
        verify(jobMapper).insert(any(JobDefinition.class));
        verify(scheduleManager).register(any());
    }

    @Test
    void rejectsJobCreationForPullScene() {
        when(sceneService.getScene("1", "s1")).thenReturn(scene("PULL"));

        assertThrows(IllegalArgumentException.class, () -> service.createJob(cmd()));

        verify(jobMapper, never()).insert(any(JobDefinition.class));
        verify(scheduleManager, never()).register(any());
    }

    @Test
    void disableJobUnregistersSchedule() {
        JobDefinition d = new JobDefinition();
        d.setId(5L);
        d.setTenantId(1L);
        d.setStatus("ACTIVE");
        when(jobMapper.selectById(5L)).thenReturn(d);

        service.disableJob("1", 5L);

        assertEquals("DISABLED", d.getStatus());
        verify(jobMapper).updateById(d);
        verify(scheduleManager).unregister(5L);
    }

    @Test
    void rejectsCrossTenantAccess() {
        JobDefinition d = new JobDefinition();
        d.setId(5L);
        d.setTenantId(999L);
        when(jobMapper.selectById(5L)).thenReturn(d);

        assertThrows(IllegalArgumentException.class, () -> service.getJob("1", 5L));
    }

    @Test
    void rejectsUnknownJob() {
        when(jobMapper.selectById(5L)).thenReturn(null);

        assertThrows(IllegalArgumentException.class, () -> service.getJob("1", 5L));
    }
}
