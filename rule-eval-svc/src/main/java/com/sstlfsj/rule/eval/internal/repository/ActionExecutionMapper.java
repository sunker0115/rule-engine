package com.sstlfsj.rule.eval.internal.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.eval.internal.domain.ActionExecutionEntity;
import org.apache.ibatis.annotations.Mapper;

/** action_execution 表 Mapper，单条 insert 即可（无批量需求）。 */
@Mapper
public interface ActionExecutionMapper extends BaseMapper<ActionExecutionEntity> {}
