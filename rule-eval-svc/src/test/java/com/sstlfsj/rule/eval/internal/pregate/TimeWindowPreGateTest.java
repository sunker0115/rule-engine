package com.sstlfsj.rule.eval.internal.pregate;

import com.sstlfsj.rule.kernel.api.model.EventSource;
import com.sstlfsj.rule.kernel.api.model.PreGateContext;
import com.sstlfsj.rule.kernel.api.model.PreGateResult;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TimeWindowPreGateTest {

    private final TimeWindowPreGate gate = new TimeWindowPreGate();

    /** 构建带指定评估时刻 occurredAt 的 PreGateContext。 */
    private PreGateContext ctx(Instant occurredAt, Map<String, Object> params) {
        RuleEvent event = new RuleEvent("1", "scene", "EVENT", "u1",
                "eid", occurredAt, Map.of(), Map.of(), EventSource.HTTP);
        return new PreGateContext("1", "scene", "u1", event, 1L, params, occurredAt);
    }

    private static final long T_FROM = Instant.parse("2026-06-01T00:00:00Z").toEpochMilli();
    private static final long T_TO = Instant.parse("2026-06-30T23:59:59Z").toEpochMilli();

    @Test
    void gateType_isTimeWindow() {
        assertEquals("TIME_WINDOW", gate.gateType());
    }

    @Test
    void withinClosedWindow_passes() {
        PreGateContext c = ctx(Instant.parse("2026-06-15T12:00:00Z"),
                Map.of("fromEpochMilli", T_FROM, "toEpochMilli", T_TO));
        assertTrue(gate.evaluate(c).passed());
    }

    @Test
    void atBoundaries_passes() {
        // 闭区间：from 与 to 边界点均命中
        assertTrue(gate.evaluate(ctx(Instant.ofEpochMilli(T_FROM),
                Map.of("fromEpochMilli", T_FROM, "toEpochMilli", T_TO))).passed());
        assertTrue(gate.evaluate(ctx(Instant.ofEpochMilli(T_TO),
                Map.of("fromEpochMilli", T_FROM, "toEpochMilli", T_TO))).passed());
    }

    @Test
    void beforeWindow_blocked() {
        PreGateResult r = gate.evaluate(ctx(Instant.parse("2026-05-31T23:59:59Z"),
                Map.of("fromEpochMilli", T_FROM, "toEpochMilli", T_TO)));
        assertFalse(r.passed());
        assertEquals("TIME_WINDOW", r.blockedBy());
    }

    @Test
    void afterWindow_blocked() {
        PreGateResult r = gate.evaluate(ctx(Instant.parse("2026-07-01T00:00:00Z"),
                Map.of("fromEpochMilli", T_FROM, "toEpochMilli", T_TO)));
        assertFalse(r.passed());
        assertEquals("TIME_WINDOW", r.blockedBy());
    }

    @Test
    void onlyFrom_blocksBeforePassesAfter() {
        Map<String, Object> p = Map.of("fromEpochMilli", T_FROM);
        assertFalse(gate.evaluate(ctx(Instant.parse("2026-05-01T00:00:00Z"), p)).passed());
        assertTrue(gate.evaluate(ctx(Instant.parse("2027-01-01T00:00:00Z"), p)).passed());
    }

    @Test
    void onlyTo_passesBeforeBlocksAfter() {
        Map<String, Object> p = Map.of("toEpochMilli", T_TO);
        assertTrue(gate.evaluate(ctx(Instant.parse("2026-01-01T00:00:00Z"), p)).passed());
        assertFalse(gate.evaluate(ctx(Instant.parse("2027-01-01T00:00:00Z"), p)).passed());
    }

    @Test
    void bothAbsent_failOpen() {
        assertTrue(gate.evaluate(ctx(Instant.now(), Map.of())).passed());
    }

    @Test
    void stringEpochMillis_parsedCorrectly() {
        // params 经 JSON 反序列化可能为 String，应容错解析
        PreGateContext c = ctx(Instant.parse("2026-06-15T12:00:00Z"),
                Map.of("fromEpochMilli", String.valueOf(T_FROM), "toEpochMilli", String.valueOf(T_TO)));
        assertTrue(gate.evaluate(c).passed());
    }

    @Test
    void paramsFromRealPreGatesJsonColumn_longEpochMillis_evaluatedCorrectly() {
        // 模拟 rule_version.pre_gates JSON 列经真实 Jackson 反序列化：13 位 epoch millis 须落 Long（非 Int），
        // TimeWindowPreGate.toLong 正确处理——单测 mock 不掉这层序列化往返
        ObjectMapper mapper = JsonMapper.builder().build();
        String json = "{\"gateType\":\"TIME_WINDOW\",\"params\":{\"fromEpochMilli\":" + T_FROM
                + ",\"toEpochMilli\":" + T_TO + "}}";
        RuleVersionSnapshot.PreGateConfig cfg = mapper.readValue(json, RuleVersionSnapshot.PreGateConfig.class);

        assertEquals("TIME_WINDOW", cfg.gateType());
        assertTrue(gate.evaluate(ctx(Instant.parse("2026-06-15T12:00:00Z"), cfg.params())).passed(),
                "窗口内应放行");
        PreGateResult before = gate.evaluate(ctx(Instant.parse("2026-05-01T00:00:00Z"), cfg.params()));
        assertFalse(before.passed(), "窗口前应拦截");
        assertEquals("TIME_WINDOW", before.blockedBy());
    }
}
