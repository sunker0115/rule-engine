package com.sstlfsj.rule.config.internal.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** MyBatis-Plus entity for the {@code metric_definition} table. */
@TableName("metric_definition")
public class MetricDefinition {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private String metricCode;
    private String name;
    private String dataType;
    private String sourceType;
    private boolean allowProvided;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getMetricCode() { return metricCode; }
    public void setMetricCode(String metricCode) { this.metricCode = metricCode; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDataType() { return dataType; }
    public void setDataType(String dataType) { this.dataType = dataType; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public boolean isAllowProvided() { return allowProvided; }
    public void setAllowProvided(boolean allowProvided) { this.allowProvided = allowProvided; }
}
