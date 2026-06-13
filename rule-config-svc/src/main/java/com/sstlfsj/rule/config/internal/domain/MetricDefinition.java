package com.sstlfsj.rule.config.internal.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.Jackson3TypeHandler;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

/** metric_definition 表实体。 */
@Getter
@Setter
@TableName(value = "metric_definition", autoResultMap = true)
public class MetricDefinition {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String metricCode;
    private Integer version;
    private String name;
    private String sourceType;
    private String dataType;
    /** 依 sourceType 异构的取数参数（开放结构）；JSON 列由 TypeHandler 转换。 */
    @TableField(typeHandler = Jackson3TypeHandler.class)
    private Map<String, Object> params;
    private Integer cacheTtlSeconds;
    private Boolean allowProvided;
    /** 是否敏感 metric：true 时其值在 trace 展示出口被读时脱敏（D71）。列名为 MySQL 保留字，反引号包裹。 */
    @TableField(value = "`sensitive`")
    private Boolean sensitive;
    private MetricStatus status;
    private String createdBy;
    private java.time.LocalDateTime createdAt;
    private String updatedBy;
    private java.time.LocalDateTime updatedAt;
}
