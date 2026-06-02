package com.sstlfsj.rule.config.internal.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** rule_definition 表实体，对应 05-storage.md §3.1 rule_definition DDL。 */
@Getter
@Setter
@TableName("rule_definition")
public class RuleDefinition {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long sceneId;
    private String code;
    private String name;
    private String description;
    private String status;
    private String kind;
    private Long currentVersion;
    private String publishedBy;
    private java.time.LocalDateTime publishedAt;
    private String createdBy;
    private java.time.LocalDateTime createdAt;
    private String updatedBy;
    private java.time.LocalDateTime updatedAt;
}
