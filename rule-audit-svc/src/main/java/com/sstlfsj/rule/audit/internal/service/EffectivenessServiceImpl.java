package com.sstlfsj.rule.audit.internal.service;

import com.sstlfsj.rule.audit.api.service.EffectivenessService;
import com.sstlfsj.rule.audit.internal.domain.ConfusionCountRow;
import com.sstlfsj.rule.audit.internal.domain.WindowTotalsRow;
import com.sstlfsj.rule.audit.internal.repository.EffectivenessReadMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** EffectivenessService 实现：按需 SQL 出原始计数，FN/TN/precision/recall/fireRate 在此推导。 */
@Service
@RequiredArgsConstructor
public class EffectivenessServiceImpl implements EffectivenessService {

    private final EffectivenessReadMapper mapper;

    @Override
    @Transactional(readOnly = true)
    public EffectivenessReport aggregate(EffectivenessQuery q) {
        String bucketUnit = q.bucket().name();
        LocalDateTime from = LocalDateTime.ofInstant(q.from(), ZoneId.systemDefault());
        LocalDateTime to = LocalDateTime.ofInstant(q.to(), ZoneId.systemDefault());

        List<ConfusionCountRow> confusions = mapper.confusionByDimension(
                q.tenantId(), q.sceneCode(), from, to, q.dimension().name(), q.positiveLabels(), bucketUnit);
        List<WindowTotalsRow> totals = mapper.windowTotals(
                q.tenantId(), q.sceneCode(), from, to, q.positiveLabels(), bucketUnit);

        // 按 bucket 归组（保序）
        Map<String, List<ConfusionCountRow>> byBucket = new LinkedHashMap<>();
        for (ConfusionCountRow c : confusions) {
            byBucket.computeIfAbsent(c.getBucket(), k -> new ArrayList<>()).add(c);
        }

        List<BucketReport> buckets = new ArrayList<>();
        for (WindowTotalsRow w : totals) {
            long totalPositive = w.getTotalPositive();
            long totalNegative = w.getTotalNegative();
            List<EffectivenessRow> rows = new ArrayList<>();
            for (ConfusionCountRow c : byBucket.getOrDefault(w.getBucket(), List.of())) {
                long tp = c.getTp();
                long fp = c.getFp();
                long fn = totalPositive - tp;   // 该 scene+桶 positive 中未命中本维度键者
                long tn = totalNegative - fp;
                Double precision = (tp + fp) == 0 ? null : (double) tp / (tp + fp);
                Double recall = totalPositive == 0 ? null : (double) tp / totalPositive;
                double fireRate = w.getTotalSessions() == 0 ? 0.0 : (double) c.getFiredTotal() / w.getTotalSessions();
                rows.add(new EffectivenessRow(c.getDimKey(), tp, fp, fn, tn, precision, recall, fireRate, c.getFiredTotal()));
            }
            // NONE 时桶标签对外呈现为 null（SQL 内归一为 'ALL'）
            String bucketLabel = q.bucket() == Bucket.NONE ? null : w.getBucket();
            buckets.add(new BucketReport(bucketLabel, w.getTotalSessions(), w.getLabeledCount(),
                    w.getTotalSessions() - w.getLabeledCount(), w.getBlockedCount(),
                    totalPositive, totalNegative, rows));
        }
        return new EffectivenessReport(buckets);
    }
}
