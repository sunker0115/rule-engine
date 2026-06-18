package com.sstlfsj.rule.eval.internal.domain;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.Jackson3TypeHandler;
import com.sstlfsj.rule.config.api.connector.ConnectorDescriptor;
import lombok.Getter;
import lombok.Setter;

/** 连接器读视图（eval 侧，只读 connector_definition）。descriptor 由 TypeHandler 转换（模板 = ConnectorDefinition）。 */
@Getter
@Setter
@TableName(value = "connector_definition", autoResultMap = true)
public class ConnectorDefinitionRow {
    private Long id;
    private Long tenantId;
    private String connectorCode;
    private String status;
    @TableField(typeHandler = Jackson3TypeHandler.class)
    private ConnectorDescriptor descriptor;
}
