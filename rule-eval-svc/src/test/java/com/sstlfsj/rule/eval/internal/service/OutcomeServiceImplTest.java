package com.sstlfsj.rule.eval.internal.service;

import com.sstlfsj.rule.eval.api.service.OutcomeService.OutcomeRecord;
import com.sstlfsj.rule.eval.internal.repository.DecisionOutcomeMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** service 是同步事务边界，转换+upsert 已下沉 Mapper default，本测试只验委托。 */
class OutcomeServiceImplTest {

    private final DecisionOutcomeMapper mapper = mock(DecisionOutcomeMapper.class);
    private final OutcomeServiceImpl service = new OutcomeServiceImpl(mapper);

    @Test
    void delegatesToMapperUpsertOutcomes() {
        List<OutcomeRecord> outcomes = List.of(
                new OutcomeRecord("evt-1", "FRAUD", null, Instant.parse("2026-06-18T10:00:00Z"), "ops", null));
        when(mapper.upsertOutcomes(eq(7L), eq(outcomes))).thenReturn(1);

        assertEquals(1, service.recordOutcomes(7L, outcomes));
        verify(mapper).upsertOutcomes(7L, outcomes);
    }
}
