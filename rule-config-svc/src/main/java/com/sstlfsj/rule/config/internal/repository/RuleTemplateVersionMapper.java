package com.sstlfsj.rule.config.internal.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.config.internal.domain.RuleTemplateVersion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/** rule_template_version 表 MyBatis-Plus Mapper。 */
@Mapper
public interface RuleTemplateVersionMapper extends BaseMapper<RuleTemplateVersion> {

    /** 查指定模板的最新 PUBLISHED 版本快照，不存在返回 null。 */
    @Select("SELECT * FROM rule_template_version WHERE template_id = #{templateId} AND status = 'PUBLISHED' ORDER BY version DESC LIMIT 1")
    RuleTemplateVersion findLatestPublished(Long templateId);

    /** 按 (templateId, version) 查指定版本快照，不存在返回 null。 */
    @Select("SELECT * FROM rule_template_version WHERE template_id = #{templateId} AND version = #{version}")
    RuleTemplateVersion findByVersion(Long templateId, Integer version);

    /** 查指定模板的 DRAFT 版本快照，不存在返回 null。 */
    @Select("SELECT * FROM rule_template_version WHERE template_id = #{templateId} AND status = 'DRAFT'")
    RuleTemplateVersion findDraft(Long templateId);
}
