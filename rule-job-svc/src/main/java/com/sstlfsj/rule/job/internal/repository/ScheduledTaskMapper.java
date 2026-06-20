package com.sstlfsj.rule.job.internal.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.job.api.TaskStatus;
import com.sstlfsj.rule.job.internal.domain.ScheduledTask;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/** scheduled_task Mapper。 */
@Mapper
public interface ScheduledTaskMapper extends BaseMapper<ScheduledTask> {

    /** 查租户某 code 的任务,不存在返回 null。 */
    default ScheduledTask findByTenantCode(Long tenantId, String code) {
        return selectOne(new LambdaQueryWrapper<ScheduledTask>()
                .eq(ScheduledTask::getTenantId, tenantId)
                .eq(ScheduledTask::getCode, code));
    }

    /** 查租户全部任务。 */
    default List<ScheduledTask> findByTenant(Long tenantId) {
        return selectList(new LambdaQueryWrapper<ScheduledTask>()
                .eq(ScheduledTask::getTenantId, tenantId)
                .orderByAsc(ScheduledTask::getId));
    }

    /** 查全部 ACTIVE 任务(启动注册用)。 */
    default List<ScheduledTask> findAllActive() {
        return selectList(new LambdaQueryWrapper<ScheduledTask>()
                .eq(ScheduledTask::getStatus, TaskStatus.ACTIVE));
    }
}
