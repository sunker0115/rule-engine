package com.sstlfsj.rule.eval.internal.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** dry_run_session 表对应的 MyBatis-Plus 实体（D7，7 天 TTL）。 */
@Getter
@Setter
@TableName("dry_run_session")
public class DryRunSession {

    @TableId(type = IdType.INPUT)
    private Long id;
    private Long tenantId;
    private String eventId;
    private String sceneCode;
    private String eventType;
    private String subjectId;
    /** 本次 dry-run 测试的规则版本 ID。 */
    private Long ruleVersionId;
    private String status;
    private String finalDecision;
    private String hitDecisions;
    private String blockedBy;
    private String errorCode;
    private LocalDateTime occurredAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Integer evalDurationMs;
    /** dry-run 试算时 EvalContext metrics 取数快照（JSON 文本），排障 / 重放对比用。 */
    private String contextSnapshot;
}
