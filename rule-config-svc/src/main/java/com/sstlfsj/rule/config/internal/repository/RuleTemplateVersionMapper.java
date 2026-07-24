package com.sstlfsj.rule.config.internal.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.config.internal.domain.RuleTemplateVersion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** rule_template_version 表 MyBatis-Plus Mapper。 */
@Mapper
public interface RuleTemplateVersionMapper extends BaseMapper<RuleTemplateVersion> {

    /** 查指定模板的最新 PUBLISHED 版本快照，不存在返回 null。 */
    default RuleTemplateVersion findLatestPublished(Long templateId) {
        return selectOne(new LambdaQueryWrapper<RuleTemplateVersion>()
                .eq(RuleTemplateVersion::getTemplateId, templateId)
                .eq(RuleTemplateVersion::getStatus, com.sstlfsj.rule.config.internal.domain.TemplateStatus.PUBLISHED)
                .orderByDesc(RuleTemplateVersion::getVersion)
                .last("LIMIT 1"));
    }

    /** 按 (templateId, version) 查指定版本快照，不存在返回 null。 */
    default RuleTemplateVersion findByVersion(Long templateId, Integer version) {
        return selectOne(new LambdaQueryWrapper<RuleTemplateVersion>()
                .eq(RuleTemplateVersion::getTemplateId, templateId)
                .eq(RuleTemplateVersion::getVersion, version));
    }

    /** 查指定模板的 DRAFT 版本快照，不存在返回 null。 */
    default RuleTemplateVersion findDraft(Long templateId) {
        return selectOne(new LambdaQueryWrapper<RuleTemplateVersion>()
                .eq(RuleTemplateVersion::getTemplateId, templateId)
                .eq(RuleTemplateVersion::getStatus, com.sstlfsj.rule.config.internal.domain.TemplateStatus.DRAFT)
                .orderByDesc(RuleTemplateVersion::getVersion)
                .last("LIMIT 1"));
    }

    /** 查指定模板的最大版本号，无记录时返回 0。 */
    @Select("SELECT COALESCE(MAX(version), 0) FROM rule_template_version WHERE template_id = #{templateId}")
    int findMaxVersion(Long templateId);

    /** 查指定模板的所有版本快照，按 version DESC 排序。 */
    default List<RuleTemplateVersion> findByTemplateId(Long templateId) {
        return selectList(new LambdaQueryWrapper<RuleTemplateVersion>()
                .eq(RuleTemplateVersion::getTemplateId, templateId)
                .orderByDesc(RuleTemplateVersion::getVersion));
    }
}
