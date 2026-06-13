package com.sstlfsj.rule.eval.internal.async;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 审计落库相关配置。
 *
 * <p>context_snapshot 开关同时控制忠实重放三件套(payload + 候选版本 id + context_snapshot)的回填：
 * 开启后每会话多几段 JSON 写，但 session 方可被忠实重放；默认开。
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
        /** 是否回填重放三件套(payload + 候选版本 id + context_snapshot)；默认 true(忠实重放开箱即用)。 */
        private boolean enabled = true;
    }
}
