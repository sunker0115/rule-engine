package com.sstlfsj.rule.audit.internal.domain;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** SQL JOIN 查询结果映射：evaluation_session + node_trace + rule_version，按规则定义 ID 过滤。 */
@Getter
@Setter
public class RuleSessionRow {
    private Long id;
    private String eventId;
    private String subjectId;
    private String status;
    private String finalDecision;
    private Integer evalDurationMs;
    private LocalDateTime startedAt;
    /** MAX(nt.rule_version_id)，同一 session 可命中多个版本时取最大值。 */
    private Long ruleVersionId;
}
