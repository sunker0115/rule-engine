package com.sstlfsj.rule.eval.internal.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.eval.internal.domain.DryRunSession;
import org.apache.ibatis.annotations.Mapper;

/** dry_run_session 表 CRUD Mapper。 */
@Mapper
public interface DryRunSessionMapper extends BaseMapper<DryRunSession> {}
