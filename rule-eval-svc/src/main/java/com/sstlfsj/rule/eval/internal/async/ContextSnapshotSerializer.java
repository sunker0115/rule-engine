package com.sstlfsj.rule.eval.internal.async;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * EvalContext 上下文快照序列化共享工具。
 *
 * <p>dry-run 与审计两条落库链路共用同一序列化逻辑，输出 {@code {"metrics":{code:rawValue},"evalNow":"<ISO>"}}；
 * ctx 为 null 或序列化抛 {@link JacksonException} 时一律返回 null（best-effort，快照可丢）。
 */
public final class ContextSnapshotSerializer {

    private static final Logger log = LoggerFactory.getLogger(ContextSnapshotSerializer.class);

    private ContextSnapshotSerializer() {
    }

    /**
     * 将评估上下文序列化为快照 JSON。
     *
     * @param objectMapper Spring 全局 ObjectMapper
     * @param ctx          评估上下文，可为 null
     * @return 快照 JSON 字符串；ctx 为 null 或序列化失败返回 null
     */
    public static String serialize(ObjectMapper objectMapper, EvalContext ctx) {
        if (ctx == null) return null;
        Map<String, Object> metrics = ctx.metrics().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey,
                        en -> en.getValue().value() != null ? en.getValue().value() : "null"));
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("metrics", metrics);
        snapshot.put("evalNow", ctx.now() != null ? ctx.now().toString() : null);
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JacksonException ex) {
            log.warn("context_snapshot 序列化失败,写 null", ex);
            return null;
        }
    }
}
