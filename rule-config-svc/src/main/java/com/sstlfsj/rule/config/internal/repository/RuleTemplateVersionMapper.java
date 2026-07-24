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
    @Select("SELECT * FROM rule_template_version WHERE template_id = #{templateId} AND status = 'DRAFT' ORDER BY version DESC LIMIT 1")
    RuleTemplateVersion findDraft(Long templateId);

    /** 查指定模板的最大版本号，无记录时返回 0。 */
    @Select("SELECT COALESCE(MAX(version), 0) FROM rule_template_version WHERE template_id = #{templateId}")
    int findMaxVersion(Long templateId);

    /** 查指定模板的所有版本快照，按 version DESC 排序。 */
    @Select("SELECT * FROM rule_template_version WHERE template_id = #{templateId} ORDER BY version DESC")
    java.util.List<RuleTemplateVersion> findByTemplateId(Long templateId);
}
