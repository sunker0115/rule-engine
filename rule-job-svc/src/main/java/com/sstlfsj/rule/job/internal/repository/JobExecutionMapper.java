package com.sstlfsj.rule.job.internal.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.job.internal.domain.JobExecution;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/** job_execution 表 MyBatis-Plus Mapper。 */
@Mapper
public interface JobExecutionMapper extends BaseMapper<JobExecution> {

    /** 查指定 Job 最近 limit 条执行记录，按触发时间倒序。 */
    default List<JobExecution> findRecent(Long jobDefinitionId, int limit) {
        return selectList(new LambdaQueryWrapper<JobExecution>()
                .eq(JobExecution::getJobDefinitionId, jobDefinitionId)
                .orderByDesc(JobExecution::getTriggerAt)
                .last("LIMIT " + Math.max(1, limit)));
    }
}
