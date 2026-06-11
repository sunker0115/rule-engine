package com.sstlfsj.rule.job.internal.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.job.internal.domain.JobDefinition;
import com.sstlfsj.rule.job.internal.domain.JobStatus;
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
                .eq(JobDefinition::getStatus, JobStatus.ACTIVE));
    }

    /** 按 (tenantId, sceneCode, code) 查 Job，不存在返回 null；供注解 Job upsert。 */
    default JobDefinition findByTenantSceneCode(Long tenantId, String sceneCode, String code) {
        return selectOne(new LambdaQueryWrapper<JobDefinition>()
                .eq(JobDefinition::getTenantId, tenantId)
                .eq(JobDefinition::getSceneCode, sceneCode)
                .eq(JobDefinition::getCode, code));
    }
}
