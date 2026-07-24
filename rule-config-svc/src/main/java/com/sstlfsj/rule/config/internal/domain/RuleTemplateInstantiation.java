package com.sstlfsj.rule.config.internal.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.Jackson3TypeHandler;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

/** rule_template_instantiation 表实体（溯源记录，可删，删了核心零影响）。 */
@Getter
@Setter
@TableName(value = "rule_template_instantiation", autoResultMap = true)
public class RuleTemplateInstantiation {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long templateId;
    private Long templateVersionId;
    /** 冗余版本号，便于查询。 */
    private Integer templateVersion;
    private Long ruleDefinitionId;
    private Long ruleVersionId;
    /** 实例化填值快照：slotKey → coerced value。 */
    @TableField(typeHandler = Jackson3TypeHandler.class)
    private Map<String, Object> slotValues;
    private java.time.LocalDateTime instantiatedAt;
    private String instantiatedBy;
}
