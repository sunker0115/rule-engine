package com.sstlfsj.rule.eval.internal.async;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 审计落库相关配置。
 *
 * <p>当前仅含 context_snapshot 回填开关：开启后每会话多一次 JSON 写，默认关。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "engine.rule.audit")
public class AuditProperties {

    /** context_snapshot 回填子配置，绑定 {@code engine.rule.audit.context-snapshot.*}。 */
    private ContextSnapshot contextSnapshot = new ContextSnapshot();

    /** context_snapshot 回填开关子配置。 */
    @Getter
    @Setter
    public static class ContextSnapshot {
        /** 是否回填 context_snapshot（默认 false）。 */
        private boolean enabled = false;
    }
}
