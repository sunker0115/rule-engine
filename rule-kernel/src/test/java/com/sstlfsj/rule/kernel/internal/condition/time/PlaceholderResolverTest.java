package com.sstlfsj.rule.kernel.internal.condition.time;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PlaceholderResolverTest {

    private EvalContext ctxWithNow(Instant now) {
        RuleEvent ev = new RuleEvent("t1", "s1", "E", "u1", "e1", now, Map.of(), Map.of(), com.sstlfsj.rule.kernel.api.model.EventSource.HTTP);
        return new EvalContext("t1", ev, null, Map.of(), now);
    }

    @Test
    void resolveDateTime_now_returnsCtxNow() {
        Instant now = Instant.parse("2026-06-01T10:00:00Z");
        assertThat(PlaceholderResolver.resolveDateTime("$now", ctxWithNow(now), ZoneOffset.UTC))
                .isEqualTo(now);
    }

    @Test
    void resolveDateTime_offsetString_parsedToInstant() {
        Instant r = PlaceholderResolver.resolveDateTime(
                "2026-06-01T00:00:00+08:00", ctxWithNow(Instant.EPOCH), ZoneOffset.UTC);
        assertThat(r).isEqualTo(Instant.parse("2026-05-31T16:00:00Z"));
    }

    @Test
    void resolveDateTime_bareDate_appliesZone() {
        Instant r = PlaceholderResolver.resolveDateTime(
                "2026-06-01", ctxWithNow(Instant.EPOCH), ZoneId.of("Asia/Shanghai"));
        assertThat(r).isEqualTo(Instant.parse("2026-05-31T16:00:00Z")); // 00:00+08 = 前一天16:00Z
    }

    @Test
    void resolveDateTime_today_returnsNull() {
        assertThat(PlaceholderResolver.resolveDateTime(
                "$today", ctxWithNow(Instant.EPOCH), ZoneOffset.UTC)).isNull();
    }

    @Test
    void resolveDateTime_unknownPlaceholder_returnsNull() {
        assertThat(PlaceholderResolver.resolveDateTime(
                "$unknown", ctxWithNow(Instant.EPOCH), ZoneOffset.UTC)).isNull();
    }

    @Test
    void resolveDate_today_projectsCtxNowToZone() {
        // 2026-06-01T16:30Z 在 Asia/Shanghai 是 2026-06-02 00:30
        Instant now = Instant.parse("2026-06-01T16:30:00Z");
        assertThat(PlaceholderResolver.resolveDate("$today", ctxWithNow(now), ZoneId.of("Asia/Shanghai")))
                .isEqualTo(LocalDate.of(2026, 6, 2));
    }

    @Test
    void resolveDate_isoString_parsed() {
        assertThat(PlaceholderResolver.resolveDate("2026-06-01", ctxWithNow(Instant.EPOCH), ZoneOffset.UTC))
                .isEqualTo(LocalDate.of(2026, 6, 1));
    }

    @Test
    void resolveDate_invalidString_returnsNull() {
        assertThat(PlaceholderResolver.resolveDate("nope", ctxWithNow(Instant.EPOCH), ZoneOffset.UTC))
                .isNull();
    }

    @Test
    void resolveTyped_nonTemporalDataType_isPassthrough() {
        Object raw = 42L;
        assertThat(PlaceholderResolver.resolveTyped("LONG", raw, ctxWithNow(Instant.EPOCH), ZoneOffset.UTC))
                .isSameAs(raw);
    }
}
