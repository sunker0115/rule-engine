package com.sstlfsj.rule.eval.internal.async;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.eval.internal.domain.EvaluationContextSnapshot;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * EvalContext 上下文快照序列化共享工具。
 *
 * <p>供生产评估审计保存上下文快照；JSON 转换由持久层 TypeHandler 负责。
 */
public final class ContextSnapshotSerializer {

    private ContextSnapshotSerializer() {
    }

    /**
     * 将评估上下文转换为快照对象。
     *
     * @param ctx 评估上下文，可为 null
     * @return 快照对象；ctx 为 null 时返回 null
     */
    public static EvaluationContextSnapshot serialize(EvalContext ctx) {
        if (ctx == null) return null;
        Map<String, Object> metrics = ctx.metrics().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey,
                        en -> en.getValue().value() != null ? en.getValue().value() : "null"));
        return new EvaluationContextSnapshot(metrics, ctx.now());
    }
}
