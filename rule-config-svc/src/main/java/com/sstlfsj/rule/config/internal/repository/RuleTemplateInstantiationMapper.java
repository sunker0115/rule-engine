package com.sstlfsj.rule.config.internal.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.config.internal.domain.RuleTemplateInstantiation;
import org.apache.ibatis.annotations.Mapper;

/** rule_template_instantiation 表 MyBatis-Plus Mapper（溯源只做 insert，不提供删除/更新语义）。 */
@Mapper
public interface RuleTemplateInstantiationMapper extends BaseMapper<RuleTemplateInstantiation> {
}
