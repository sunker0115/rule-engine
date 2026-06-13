package com.sstlfsj.rule.kernel.internal.evaluator;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.Subject;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 把 EvalContext 投影成脚本引擎用的只读变量绑定面:
 * metrics.&lt;code&gt; / payload.&lt;field&gt; / subject.&lt;attr&gt; / now。
 * ScriptExecutor 与(将来)方案 B 共用,引擎无关。
 */
public final class ScriptBindings {

    private ScriptBindings() {}

    /**
     * 构造顶层命名空间绑定 map(metrics/payload/subject/now)。
     *
     * @param ctx 评估上下文(subject 可为 null)
     * @return 不可变绑定 map
     */
    public static Map<String, Object> from(EvalContext ctx) {
        Map<String, Object> metrics = HashMap.newHashMap(ctx.metrics().size());
        for (Map.Entry<String, MetricValue> e : ctx.metrics().entrySet()) {
            // 取数失败时 value 为 null,原样暴露(引擎/脚本自行处理 null)
            metrics.put(e.getKey(), e.getValue().value());
        }
        Subject subject = ctx.subject();
        Map<String, Object> subjectAttrs = subject == null ? Map.of() : subject.attributes();
        return Map.of(
                // metrics 子 map 允许 null value(取数失败),故用 unmodifiableMap 而非 Map.copyOf
                "metrics", Collections.unmodifiableMap(metrics),
                "payload", ctx.event().payload(),
                "subject", subjectAttrs,
                "now", ctx.now());
    }
}
