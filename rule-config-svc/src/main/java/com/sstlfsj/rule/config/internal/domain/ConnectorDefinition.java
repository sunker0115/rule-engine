package com.sstlfsj.rule.config.internal.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.Jackson3TypeHandler;
import com.sstlfsj.rule.config.api.connector.ConnectorDescriptor;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 连接器定义实体。descriptor 整体作为单 JSON 列由 TypeHandler 转换（模板 = RuleVersion）。 */
@Getter
@Setter
@TableName(value = "connector_definition", autoResultMap = true)
public class ConnectorDefinition {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String connectorCode;
    private String name;

    @TableField(typeHandler = Jackson3TypeHandler.class)
    private ConnectorDescriptor descriptor;

    private ConnectorStatus status;
    private String createdBy;
    @TableField("created_at")
    private LocalDateTime createdAt;
    private String updatedBy;
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
