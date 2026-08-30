package com.sstlfsj.rule.eval.internal.async;

import com.sstlfsj.rule.eval.internal.domain.EvaluationContextSnapshot;

import java.time.Instant;
import java.util.Map;

/**
 * 将 context_snapshot JSON（{@code {"metrics":{code:rawValue},"evalNow":"<ISO>"}}）反序列化回
 * metrics 原值 map + evalNow，供忠实重放回灌。与 {@link ContextSnapshotSerializer} 互为逆操作。
 */
public final class ContextSnapshotDeserializer {

    private ContextSnapshotDeserializer() {
    }

    /** 反序列化结果：metrics 原值（{@code code -> rawValue}）+ 评估时刻（可为 null）。 */
    public record Snapshot(Map<String, Object> metrics, Instant evalNow) {
    }

    /**
     * 反序列化 context_snapshot。
     *
     * @param snapshot context_snapshot 列内容
     * @return 解析结果；snapshot 为 null 时 metrics 为空 map、evalNow 为 null
     */
    public static Snapshot deserialize(EvaluationContextSnapshot snapshot) {
        if (snapshot == null) return new Snapshot(Map.of(), null);
        return new Snapshot(snapshot.metrics() != null ? snapshot.metrics() : Map.of(), snapshot.evalNow());
    }
}
