package com.sstlfsj.rule.config.internal.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.Jackson3TypeHandler;
import com.sstlfsj.rule.config.api.dto.PayloadFieldSpec;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

/** scene 表实体，对应 05-storage.md §3.1 scene DDL。 */
@Getter
@Setter
@TableName(value = "scene", autoResultMap = true)
public class SceneDef {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String code;
    private String name;
    private String description;
    private DominantMode dominantMode;
    private DecisionStrategy decisionStrategy;
    private com.sstlfsj.rule.kernel.api.model.SubjectType subjectType;
    /** 允许的 eventType 白名单；JSON 列由 TypeHandler 转换。 */
    @TableField(typeHandler = Jackson3TypeHandler.class)
    private List<String> eventTypes;
    /** payloadSchema 字段类型声明；JSON 列由 TypeHandler 转换。 */
    @TableField(typeHandler = Jackson3TypeHandler.class)
    private List<PayloadFieldSpec> payloadSchema;
    /** Scene 默认参数（开放结构）；JSON 列由 TypeHandler 转换。 */
    @TableField(typeHandler = Jackson3TypeHandler.class)
    private Map<String, Object> defaultParams;
    private SceneStatus status;
    private String createdBy;
    private java.time.LocalDateTime createdAt;
    private String updatedBy;
    private java.time.LocalDateTime updatedAt;
}
