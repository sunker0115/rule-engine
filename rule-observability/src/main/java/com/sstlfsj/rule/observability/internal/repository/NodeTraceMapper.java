package com.sstlfsj.rule.observability.internal.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.observability.internal.domain.NodeTraceEntity;
import org.apache.ibatis.annotations.Mapper;

/** node_trace 表 MyBatis-Plus Mapper（批量写，异步通道）。 */
@Mapper
public interface NodeTraceMapper extends BaseMapper<NodeTraceEntity> {}
