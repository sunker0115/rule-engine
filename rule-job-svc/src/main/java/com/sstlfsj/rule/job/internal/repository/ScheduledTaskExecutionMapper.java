package com.sstlfsj.rule.job.internal.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sstlfsj.rule.job.internal.domain.ScheduledTaskExecution;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/** scheduled_task_execution Mapper。 */
@Mapper
public interface ScheduledTaskExecutionMapper extends BaseMapper<ScheduledTaskExecution> {

    /** 某任务最近 limit 条执行记录,按触发时间倒序。 */
    default List<ScheduledTaskExecution> recentByTask(Long scheduledTaskId, int limit) {
        return selectList(new Page<ScheduledTaskExecution>(1, limit),
                new LambdaQueryWrapper<ScheduledTaskExecution>()
                        .eq(ScheduledTaskExecution::getScheduledTaskId, scheduledTaskId)
                        .orderByDesc(ScheduledTaskExecution::getTriggerAt));
    }
}
