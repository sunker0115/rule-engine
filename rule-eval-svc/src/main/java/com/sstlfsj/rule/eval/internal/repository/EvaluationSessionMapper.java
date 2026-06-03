package com.sstlfsj.rule.eval.internal.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.eval.internal.domain.EvaluationSession;
import org.apache.ibatis.annotations.Mapper;

/** evaluation_session 表 CRUD Mapper。 */
@Mapper
public interface EvaluationSessionMapper extends BaseMapper<EvaluationSession> {}
