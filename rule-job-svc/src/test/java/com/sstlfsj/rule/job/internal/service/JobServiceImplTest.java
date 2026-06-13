package com.sstlfsj.rule.job.internal.service;

import com.sstlfsj.rule.config.api.dto.SceneDetailDto;
import com.sstlfsj.rule.config.api.service.SceneService;
import com.sstlfsj.rule.job.api.BeanMethodQuery;
import com.sstlfsj.rule.job.api.dto.JobDefinitionDto;
import com.sstlfsj.rule.job.internal.domain.JobDefinition;
import com.sstlfsj.rule.job.internal.domain.JobStatus;
import com.sstlfsj.rule.job.internal.repository.JobDefinitionMapper;
import com.sstlfsj.rule.job.internal.repository.JobExecutionMapper;
import com.sstlfsj.rule.job.internal.runner.JobRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

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
    @Spy
    ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    JobServiceImpl service;

    private SceneDetailDto scene(String mode) {
        return new SceneDetailDto(1L, "1", "s1", "name", null, mode, "USER",
                List.of(), List.of(), Map.of(), "ACTIVE");
    }

    private JobDefinition job(JobStatus status) {
        JobDefinition d = new JobDefinition();
        d.setId(5L);
        d.setTenantId(1L);
        d.setSceneCode("s1");
        d.setStatus(status);
        return d;
    }

    @Test
    void enableJobForPushSceneRegistersSchedule() {
        JobDefinition d = job(JobStatus.DISABLED);
        when(jobMapper.selectById(5L)).thenReturn(d);
        when(sceneService.getScene("1", "s1")).thenReturn(scene("PUSH"));

        service.enableJob("1", 5L);

        assertEquals(JobStatus.ACTIVE, d.getStatus());
        verify(scheduleManager).register(d);
    }

    @Test
    void rejectsEnableForPullScene() {
        JobDefinition d = job(JobStatus.DISABLED);
        when(jobMapper.selectById(5L)).thenReturn(d);
        when(sceneService.getScene("1", "s1")).thenReturn(scene("PULL"));

        assertThrows(IllegalArgumentException.class, () -> service.enableJob("1", 5L));

        verify(scheduleManager, never()).register(any());
    }

    @Test
    void disableJobUnregistersSchedule() {
        JobDefinition d = job(JobStatus.ACTIVE);
        when(jobMapper.selectById(5L)).thenReturn(d);

        service.disableJob("1", 5L);

        assertEquals(JobStatus.DISABLED, d.getStatus());
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

    @Test
    void getJobParsesSubjectQueryToTypedUnion() {
        JobDefinition d = job(JobStatus.ACTIVE);
        d.setSubjectQuery("{\"type\":\"BEAN_METHOD\",\"ref\":\"a#b\"}");
        when(jobMapper.selectById(5L)).thenReturn(d);

        JobDefinitionDto dto = service.getJob("1", 5L);

        assertThat(dto.subjectQuery()).isInstanceOf(BeanMethodQuery.class);
        assertThat(((BeanMethodQuery) dto.subjectQuery()).ref()).isEqualTo("a#b");
    }
}
