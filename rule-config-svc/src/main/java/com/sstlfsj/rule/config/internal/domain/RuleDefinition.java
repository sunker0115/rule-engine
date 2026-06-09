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
    private RuleDefinitionStatus status;
    private String kind;
    private Long currentVersion;
    private String publishedBy;
    private java.time.LocalDateTime publishedAt;
    private String createdBy;
    private java.time.LocalDateTime createdAt;
    private String updatedBy;
    private java.time.LocalDateTime updatedAt;

    /**
     * 构造草稿态规则定义：status 固定为 DRAFT、createdAt 取当前时刻，其余业务字段由入参给定。
     *
     * @param tenantId  租户 id
     * @param sceneId   场景 id
     * @param code      规则编码
     * @param name      规则名称
     * @param kind      规则类型标签
     * @param createdBy 创建人
     * @return 草稿态 RuleDefinition（id 由插入时回填）
     */
    public static RuleDefinition draft(Long tenantId, Long sceneId, String code, String name,
                                       String kind, String createdBy) {
        RuleDefinition rd = new RuleDefinition();
        rd.setTenantId(tenantId);
        rd.setSceneId(sceneId);
        rd.setCode(code);
        rd.setName(name);
        rd.setStatus(RuleDefinitionStatus.DRAFT);
        rd.setKind(kind);
        rd.setCreatedBy(createdBy);
        rd.setCreatedAt(java.time.LocalDateTime.now());
        return rd;
    }
}
