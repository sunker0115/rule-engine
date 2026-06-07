package com.sstlfsj.rule.job.internal.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.job.internal.domain.JobDefinition;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/** job_definition 表 MyBatis-Plus Mapper。 */
@Mapper
public interface JobDefinitionMapper extends BaseMapper<JobDefinition> {

    /** 按 tenantId 查该租户全部 Job，按 id 倒序。 */
    default List<JobDefinition> findByTenantId(Long tenantId) {
        return selectList(new LambdaQueryWrapper<JobDefinition>()
                .eq(JobDefinition::getTenantId, tenantId)
                .orderByDesc(JobDefinition::getId));
    }

    /** 查全部 ACTIVE Job（跨租户），供启动期注册到调度器。 */
    default List<JobDefinition> findAllActive() {
        return selectList(new LambdaQueryWrapper<JobDefinition>()
                .eq(JobDefinition::getStatus, "ACTIVE"));
    }
}
