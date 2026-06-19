package com.sstlfsj.rule.audit.api.service;

import com.sstlfsj.rule.audit.api.service.EffectivenessService.Bucket;
import com.sstlfsj.rule.audit.api.service.EffectivenessService.Dimension;
import com.sstlfsj.rule.audit.api.service.EffectivenessService.EffectivenessQuery;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 EffectivenessService 是公开接口且枚举 / 查询记录形状符合契约。 */
class EffectivenessServiceTest {

    @Test
    void isInterface() {
        assertThat(EffectivenessService.class.isInterface()).isTrue();
    }

    @Test
    void dimensionAndBucket_enumValues() {
        assertThat(Dimension.values()).containsExactly(Dimension.RULE_VERSION, Dimension.DECISION);
        assertThat(Bucket.values()).containsExactly(Bucket.NONE, Bucket.DAY, Bucket.WEEK);
    }

    @Test
    void hasAggregateMethod() throws NoSuchMethodException {
        var method = EffectivenessService.class.getMethod("aggregate", EffectivenessQuery.class);
        assertThat(method.getReturnType())
                .isEqualTo(EffectivenessService.EffectivenessReport.class);
    }

    @Test
    void query_carriesAllFields() {
        EffectivenessQuery q = new EffectivenessQuery(1L, "s",
                Instant.parse("2026-06-01T00:00:00Z"), Instant.parse("2026-06-19T00:00:00Z"),
                List.of("FRAUD"), Dimension.RULE_VERSION, Bucket.DAY);
        assertThat(q.tenantId()).isEqualTo(1L);
        assertThat(q.positiveLabels()).containsExactly("FRAUD");
        assertThat(q.bucket()).isEqualTo(Bucket.DAY);
    }
}
