package com.sstlfsj.rule.audit.internal.service;

import com.sstlfsj.rule.audit.api.service.EffectivenessService.*;
import com.sstlfsj.rule.audit.internal.domain.ConfusionCountRow;
import com.sstlfsj.rule.audit.internal.domain.WindowTotalsRow;
import com.sstlfsj.rule.audit.internal.repository.EffectivenessReadMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EffectivenessServiceImplTest {

    private final EffectivenessReadMapper mapper = mock(EffectivenessReadMapper.class);
    private final EffectivenessServiceImpl service = new EffectivenessServiceImpl(mapper);

    private static ConfusionCountRow confusion(String bucket, String dim, long tp, long fp, long fired) {
        ConfusionCountRow r = new ConfusionCountRow();
        r.setBucket(bucket);
        r.setDimKey(dim);
        r.setTp(tp);
        r.setFp(fp);
        r.setFiredTotal(fired);
        return r;
    }

    private static WindowTotalsRow totals(String bucket, long sessions, long labeled,
                                          long pos, long neg, long blocked) {
        WindowTotalsRow w = new WindowTotalsRow();
        w.setBucket(bucket);
        w.setTotalSessions(sessions);
        w.setLabeledCount(labeled);
        w.setTotalPositive(pos);
        w.setTotalNegative(neg);
        w.setBlockedCount(blocked);
        return w;
    }

    private static EffectivenessQuery query(Bucket bucket) {
        return new EffectivenessQuery(1L, "fraud_check",
                Instant.parse("2026-06-01T00:00:00Z"), Instant.parse("2026-06-19T00:00:00Z"),
                List.of("FRAUD"), Dimension.RULE_VERSION, bucket);
    }

    @Test
    void derivesConfusionMatrixAndRatios() {
        // 桶 ALL：总 100 session，labeled 80，positive 20，negative 60，blocked 5
        when(mapper.windowTotals(any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(totals("ALL", 100, 80, 20, 60, 5)));
        // 规则 1001：firedTotal 30，TP=15、FP=10（labeled fired=25，余 5 未标签）
        when(mapper.confusionByDimension(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(confusion("ALL", "1001", 15, 10, 30)));

        EffectivenessReport rep = service.aggregate(query(Bucket.NONE));

        assertEquals(1, rep.buckets().size());
        BucketReport b = rep.buckets().get(0);
        assertNull(b.bucket());               // NONE → null
        assertEquals(100, b.totalSessions());
        assertEquals(20, b.unlabeledCount()); // 100 - 80
        assertEquals(5, b.blockedCount());

        EffectivenessRow row = b.rows().get(0);
        assertEquals("1001", row.dimensionKey());
        assertEquals(15, row.tp());
        assertEquals(10, row.fp());
        assertEquals(5, row.fn());            // totalPositive 20 - tp 15
        assertEquals(50, row.tn());           // totalNegative 60 - fp 10
        assertEquals(15.0 / 25, row.precision(), 1e-9);
        assertEquals(15.0 / 20, row.recall(), 1e-9);
        assertEquals(30.0 / 100, row.fireRate(), 1e-9);
    }

    @Test
    void zeroDenominators_yieldNullRatios() {
        when(mapper.windowTotals(any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(totals("ALL", 0, 0, 0, 0, 0)));
        when(mapper.confusionByDimension(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(confusion("ALL", "1001", 0, 0, 0)));

        EffectivenessRow row = service.aggregate(query(Bucket.NONE)).buckets().get(0).rows().get(0);
        assertNull(row.precision());
        assertNull(row.recall());
        assertEquals(0.0, row.fireRate());
    }

    @Test
    void multipleBuckets_keepBucketLabelAndGroupRows() {
        when(mapper.windowTotals(any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(totals("2026-06-17", 10, 10, 4, 6, 0),
                                    totals("2026-06-18", 20, 20, 10, 10, 0)));
        when(mapper.confusionByDimension(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(confusion("2026-06-17", "1001", 3, 1, 4),
                                    confusion("2026-06-18", "1001", 8, 2, 10)));

        EffectivenessReport rep = service.aggregate(query(Bucket.DAY));

        assertEquals(2, rep.buckets().size());
        BucketReport d17 = rep.buckets().get(0);
        assertEquals("2026-06-17", d17.bucket());   // DAY → 保留桶标签
        assertEquals(1, d17.rows().get(0).fn());     // pos 4 - tp 3
        BucketReport d18 = rep.buckets().get(1);
        assertEquals(2, d18.rows().get(0).fn());     // pos 10 - tp 8
    }

    @Test
    void bucketWithoutConfusionRows_yieldsEmptyRowsButKeepsTotals() {
        // 某桶有 session 但无任何命中维度键（全 MISS）→ rows 空，诚实回报总量仍在
        when(mapper.windowTotals(any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(totals("ALL", 7, 3, 1, 2, 1)));
        when(mapper.confusionByDimension(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of());

        BucketReport b = service.aggregate(query(Bucket.NONE)).buckets().get(0);
        assertTrue(b.rows().isEmpty());
        assertEquals(7, b.totalSessions());
        assertEquals(4, b.unlabeledCount());
        assertEquals(1, b.blockedCount());
    }
}
