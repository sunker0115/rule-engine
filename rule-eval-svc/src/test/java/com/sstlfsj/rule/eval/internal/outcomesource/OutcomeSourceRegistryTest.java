package com.sstlfsj.rule.eval.internal.outcomesource;

import com.sstlfsj.rule.eval.api.service.OutcomePullResult;
import com.sstlfsj.rule.eval.api.service.OutcomeSource;
import com.sstlfsj.rule.eval.api.service.OutcomeSourceConfig;
import com.sstlfsj.rule.eval.api.service.SqlOutcomeSourceConfig;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OutcomeSourceRegistryTest {

    @Test
    void routesToSourceByConfigType() {
        SqlOutcomeSource sqlSource = mock(SqlOutcomeSource.class);
        OutcomePullResult expected = new OutcomePullResult(List.of(), null);
        when(sqlSource.configType()).thenReturn(SqlOutcomeSourceConfig.class);
        SqlOutcomeSourceConfig cfg = new SqlOutcomeSourceConfig("ds", "select 1");
        when(sqlSource.pull(any(), any(), any())).thenReturn(expected);

        OutcomeSourceRegistry registry = new OutcomeSourceRegistry(List.of(sqlSource));

        assertThat(registry.pull(cfg, null, 1L)).isSameAs(expected);
    }

    @Test
    void unknownConfigTypeThrows() {
        OutcomeSourceRegistry registry = new OutcomeSourceRegistry(List.of());

        assertThatThrownBy(() -> registry.pull(new SqlOutcomeSourceConfig("ds", "select 1"), null, 1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("无 OutcomeSource 处理 configType=SqlOutcomeSourceConfig");
    }

    @Test
    void duplicateConfigTypeThrowsAtConstruction() {
        SqlOutcomeSource a = mock(SqlOutcomeSource.class);
        SqlOutcomeSource b = mock(SqlOutcomeSource.class);
        when(a.configType()).thenReturn(SqlOutcomeSourceConfig.class);
        when(b.configType()).thenReturn(SqlOutcomeSourceConfig.class);

        assertThatThrownBy(() -> new OutcomeSourceRegistry(List.of(a, b)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("多个 OutcomeSource 声明同一 configType");
    }
}
