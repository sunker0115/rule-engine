package com.sstlfsj.rule.config.api.dto;

import com.sstlfsj.rule.config.internal.domain.RuleTemplate;
import com.sstlfsj.rule.config.internal.domain.RuleTemplateVersion;

/** 模板详情：身份 + 版本快照组合，供 getVersion 系列接口返回。 */
public record TemplateDetail(RuleTemplate template, RuleTemplateVersion version) {}
