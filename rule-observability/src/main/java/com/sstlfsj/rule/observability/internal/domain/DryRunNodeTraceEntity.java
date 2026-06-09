package com.sstlfsj.rule.observability.internal.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** dry_run_node_trace 表实体（dry-run 评估链路隔离写库）。 */
@Getter
@Setter
@TableName("dry_run_node_trace")
public class DryRunNodeTraceEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long dryRunSessionId;
    private Long tenantId;
    private Long ruleVersionId;
    private String nodePath;
    private String nodeType;
    private String conditionType;
    private String metricCode;
    private String displayLabel;
    private String params;
    private String actualValue;
    private Boolean result;
    private String errorCode;
    private String valueSource;
    private LocalDateTime evaluatedAt;
}
