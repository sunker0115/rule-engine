package com.sstlfsj.rule.audit.internal.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** node_trace 表只读映射。node_path 格式为点分数字路径，如 "0.1.2"。 */
@Getter
@Setter
@TableName("node_trace")
public class NodeTraceRow {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long evaluationSessionId;
    private Long tenantId;
    private Long ruleVersionId;
    private String ruleCode;
    private Long ruleVersion;
    private String nodePath;
    private String nodeType;
    private String conditionType;
    private String metricCode;
    private String params;
    private String actualValue;
    private Boolean result;
    private String errorCode;
    private String valueSource;
}
