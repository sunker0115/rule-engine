package com.sstlfsj.rule.audit.internal.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/** evaluation_session 表只读映射（audit-svc 内部用，不跨模块引用）。 */
@Getter
@Setter
@TableName("evaluation_session")
public class EvalSessionRow {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String eventId;
    private String sceneCode;
    private String status;
    private String finalDecision;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Integer evalDurationMs;
}
