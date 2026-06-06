package com.sstlfsj.rule.config.internal.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** metric_definition 表实体。 */
@Getter
@Setter
@TableName("metric_definition")
public class MetricDefinition {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String metricCode;
    private Integer version;
    private String name;
    private String sourceType;
    private String dataType;
    private String params;
    private Integer cacheTtlSeconds;
    private Boolean allowProvided;
    private String status;
    private String createdBy;
    private java.time.LocalDateTime createdAt;
    private String updatedBy;
    private java.time.LocalDateTime updatedAt;
}
