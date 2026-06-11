package com.sstlfsj.rule.observability.internal.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sstlfsj.rule.kernel.api.model.ValueSource;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/** node_trace 表实体（D21：异步批量落库）。 */
@Getter
@Setter
@TableName("node_trace")
public class NodeTraceEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long evaluationSessionId;
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
    private ValueSource valueSource;
    private LocalDateTime evaluatedAt;
}
