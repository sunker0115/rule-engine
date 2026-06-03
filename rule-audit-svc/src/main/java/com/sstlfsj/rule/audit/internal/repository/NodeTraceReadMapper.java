package com.sstlfsj.rule.audit.internal.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.audit.internal.domain.NodeTraceRow;
import org.apache.ibatis.annotations.Mapper;

/** node_trace 只读 Mapper。 */
@Mapper
public interface NodeTraceReadMapper extends BaseMapper<NodeTraceRow> {}
