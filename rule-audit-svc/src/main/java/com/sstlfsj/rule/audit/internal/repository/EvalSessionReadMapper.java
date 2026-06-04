package com.sstlfsj.rule.audit.internal.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.audit.internal.domain.EvalSessionRow;
import org.apache.ibatis.annotations.Mapper;

/** evaluation_session 只读 Mapper（audit-svc 自有，不共享 eval-svc 的 internal）。 */
@Mapper
public interface EvalSessionReadMapper extends BaseMapper<EvalSessionRow> {}
