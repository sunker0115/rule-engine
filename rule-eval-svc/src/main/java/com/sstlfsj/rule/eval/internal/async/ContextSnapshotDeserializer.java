package com.sstlfsj.rule.eval.internal.async;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

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
     * @param om   全局 ObjectMapper
     * @param json context_snapshot 列内容
     * @return 解析结果；json 为 null/空时 metrics 为空 map、evalNow 为 null
     */
    @SuppressWarnings("unchecked")
    public static Snapshot deserialize(ObjectMapper om, String json) {
        if (json == null || json.isBlank()) return new Snapshot(Map.of(), null);
        Map<String, Object> root = om.readValue(json, new TypeReference<Map<String, Object>>() {});
        Map<String, Object> metrics = root.get("metrics") instanceof Map
                ? (Map<String, Object>) root.get("metrics") : Map.of();
        Object evalNow = root.get("evalNow");
        Instant ts = evalNow != null ? Instant.parse(evalNow.toString()) : null;
        return new Snapshot(metrics, ts);
    }
}
