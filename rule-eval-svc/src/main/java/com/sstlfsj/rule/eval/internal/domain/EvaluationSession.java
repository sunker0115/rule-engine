package com.sstlfsj.rule.eval.internal.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sstlfsj.rule.eval.internal.repository.BestEffortJsonTypeHandler;
import com.sstlfsj.rule.kernel.api.model.EventSource;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** evaluation_session 表对应的 MyBatis-Plus 实体（D11/D21 同步写）。 */
@Getter
@Setter
@TableName(value = "evaluation_session", autoResultMap = true)
public class EvaluationSession {

    // 客户端赋值（请求线程 snowflake）：异步落库需提前确定 id，供 node_trace/action 关联
    @TableId(type = IdType.INPUT)
    private Long id;
    private Long tenantId;
    private String eventId;
    private String sceneCode;
    private String eventType;
    private String subjectId;
    /** 事件渠道：HTTP / MQ / JOB / SDK / REPLAY（取自 RuleEvent.source）。 */
    private EventSource source;
    /** 评估模式：PUSH（异步）/ PULL（同步），由 EvalService 入口判定。 */
    private EvalMode mode;
    /** 状态：PENDING / HIT / MISS / BLOCKED / ERROR / FAILED。 */
    private SessionStatus status;
    private String finalDecision;
    @TableField(typeHandler = BestEffortJsonTypeHandler.class)
    private List<HitDecision> hitDecisions;
    private String blockedBy;
    private String errorCode;
    private Integer candidateRuleCount;
    private Integer hitRuleCount;
    /** SCORECARD 累计分；AST_BOOLEAN 等无分场景为 null。 */
    private Double score;
    /** DECISION_TREE 主分类（finalDecision 同源）；其他 kind 为 null。 */
    private String category;
    private LocalDateTime occurredAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Integer evalDurationMs;
    /** EvalContext metrics 取数快照（JSON 文本），构建失败时为 null。 */
    @TableField(typeHandler = BestEffortJsonTypeHandler.class)
    private EvaluationContextSnapshot contextSnapshot;
    /** 评估事件原始 payload(JSON 文本);忠实重放用,未捕获时 null。 */
    @TableField(typeHandler = BestEffortJsonTypeHandler.class)
    private Map<String, Object> payload;
    /** 当时候选规则版本 id 列表(JSON 文本);忠实重放用,未捕获时 null。 */
    @TableField(typeHandler = BestEffortJsonTypeHandler.class)
    private List<Long> candidateRuleVersionIds;
}
