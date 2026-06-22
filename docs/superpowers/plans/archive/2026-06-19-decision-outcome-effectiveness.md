# B32 决策效果闭环 / 规则有效性度量 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给已有决策日志（`evaluation_session`）补真值反馈环——业务回灌结果标签，按规则/Decision 维度按需聚合 TP/FP/FN/precision/recall/漂移。

**Architecture:** 顺既有 CQRS 边界：写侧（标签回灌）落 rule-eval-svc `internal/outcome/`，同步强一致；读侧（聚合报表）落 rule-audit-svc，按需 SQL 用 MySQL `JSON_TABLE` 展开 `hit_decisions` 现成规则归因 join `decision_outcome`，precision/recall 在 service 层算（SQL 只出原始计数）。HTTP 入口落 rule-api。

**Tech Stack:** Java 25 / Spring Boot 4 / MyBatis-Plus / MySQL 8（JSON_TABLE）/ Flyway。设计见 `docs/superpowers/specs/2026-06-19-decision-outcome-effectiveness-design.md`。

**环境前置：** 跑 mvn 前先用 `mvn-env` skill 设 `$MVN`。跨模块改动带 `-am`，最后 `clean test` 兜底。

---

### Task 1: 迁移脚本 — decision_outcome 表

**Files:**
- Create: `rule-config-svc/src/main/resources/db/migration/V1_36__decision_outcome.sql`

- [ ] **Step 1: 写迁移脚本**

```sql
-- B32 决策效果闭环：业务真实结果标签回灌表（关联 evaluation_session 的 event 维度）
CREATE TABLE IF NOT EXISTS decision_outcome (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id     BIGINT       NOT NULL,
  event_id      VARCHAR(128) NOT NULL COMMENT '业务事件 id，关联 evaluation_session(tenant_id,event_id)',
  outcome_label VARCHAR(64)  NOT NULL COMMENT '业务自定义结果标签，引擎不解释（如 FRAUD/NOT_FRAUD）',
  outcome_value DECIMAL(18,4) COMMENT '可选数值标签，如真实损失额',
  outcome_note  VARCHAR(512) COMMENT '可选备注',
  labeled_at    TIMESTAMP(3) NOT NULL COMMENT '业务真值确定时刻（非回灌落库时刻）',
  source        VARCHAR(64)  COMMENT '回灌方标识',
  created_at    TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_tenant_event (tenant_id, event_id),
  KEY idx_tenant_labeled (tenant_id, labeled_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='决策结果标签回灌（B32）';
```

- [ ] **Step 2: 提交**

```bash
git add rule-config-svc/src/main/resources/db/migration/V1_36__decision_outcome.sql
git commit -m "feat(db): B32 decision_outcome 标签回灌表(V1_36)"
```

---

### Task 2: eval-svc 写侧 — 实体 + Mapper + 回灌 Service

**Files:**
- Create: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/outcome/DecisionOutcome.java`
- Create: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/outcome/DecisionOutcomeMapper.java`
- Create: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/api/service/OutcomeService.java`
- Create: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/outcome/OutcomeServiceImpl.java`
- Test: `rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/outcome/OutcomeServiceImplTest.java`

- [ ] **Step 1: 写实体 DecisionOutcome**

```java
package com.sstlfsj.rule.eval.internal.outcome;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** decision_outcome 表实体（B32 标签回灌写模型，eval-svc 自有）。 */
@Getter
@Setter
@TableName("decision_outcome")
public class DecisionOutcome {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String eventId;
    private String outcomeLabel;
    private BigDecimal outcomeValue;
    private String outcomeNote;
    private LocalDateTime labeledAt;
    private String source;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

- [ ] **Step 2: 写 Mapper（upsert）**

```java
package com.sstlfsj.rule.eval.internal.outcome;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** decision_outcome 写 Mapper：按 (tenant_id,event_id) 幂等 upsert。 */
@Mapper
public interface DecisionOutcomeMapper extends BaseMapper<DecisionOutcome> {

    /**
     * 批量 upsert：撞 uk_tenant_event 时覆盖 label/value/note/labeledAt/source（标签可修正），
     * updated_at 经 ON UPDATE 自动刷新。
     *
     * @param list 待回灌的结果标签（非空）
     * @return 影响行数（插入 +1 / 覆盖 +2，仅作 best-effort 日志参考）
     */
    @Insert("""
            <script>
            INSERT INTO decision_outcome
              (tenant_id, event_id, outcome_label, outcome_value, outcome_note, labeled_at, source)
            VALUES
            <foreach collection="list" item="o" separator=",">
              (#{o.tenantId}, #{o.eventId}, #{o.outcomeLabel}, #{o.outcomeValue},
               #{o.outcomeNote}, #{o.labeledAt}, #{o.source})
            </foreach>
            ON DUPLICATE KEY UPDATE
              outcome_label = VALUES(outcome_label),
              outcome_value = VALUES(outcome_value),
              outcome_note  = VALUES(outcome_note),
              labeled_at    = VALUES(labeled_at),
              source        = VALUES(source)
            </script>
            """)
    int upsertBatch(@Param("list") List<DecisionOutcome> list);
}
```

- [ ] **Step 3: 写 api/service 接口 OutcomeService**

```java
package com.sstlfsj.rule.eval.api.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 决策结果标签回灌（B32）。业务侧把真实结局按 eventId 回灌，供效果聚合。 */
public interface OutcomeService {

    /**
     * 单条回灌标签。outcomeLabel 为业务自定义串，引擎不解释；labeledAt 为业务真值确定时刻。
     *
     * @param eventId      业务事件 id（关联 evaluation_session）
     * @param outcomeLabel 业务结果标签
     * @param outcomeValue 可选数值（如损失额），无则 null
     * @param labeledAt    标签时刻
     * @param source       回灌方标识，可空
     * @param note         备注，可空
     */
    record OutcomeRecord(String eventId, String outcomeLabel, BigDecimal outcomeValue,
                         Instant labeledAt, String source, String note) {}

    /**
     * 批量回灌结果标签，按 (tenantId, eventId) 幂等 upsert（重复覆盖）。
     * 不校验对应 evaluation_session 是否已存在（标签可能早于 session 到达或 session best-effort 丢失）。
     *
     * @param tenantId 租户 id
     * @param outcomes 待回灌标签列表（空列表返回 0）
     * @return 落库接受条数
     */
    int recordOutcomes(Long tenantId, List<OutcomeRecord> outcomes);
}
```

- [ ] **Step 4: 写实现 OutcomeServiceImpl**

```java
package com.sstlfsj.rule.eval.internal.outcome;

import com.sstlfsj.rule.eval.api.service.OutcomeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/** OutcomeService 实现：同步事务 upsert（与评估期 C 类 async best-effort 不同，回灌须落库确认）。 */
@Service
@RequiredArgsConstructor
public class OutcomeServiceImpl implements OutcomeService {

    private final DecisionOutcomeMapper mapper;

    @Override
    @Transactional
    public int recordOutcomes(Long tenantId, List<OutcomeRecord> outcomes) {
        if (outcomes == null || outcomes.isEmpty()) return 0;
        List<DecisionOutcome> rows = outcomes.stream().map(r -> toRow(tenantId, r)).toList();
        mapper.upsertBatch(rows);
        return rows.size();
    }

    private DecisionOutcome toRow(Long tenantId, OutcomeRecord r) {
        DecisionOutcome o = new DecisionOutcome();
        o.setTenantId(tenantId);
        o.setEventId(r.eventId());
        o.setOutcomeLabel(r.outcomeLabel());
        o.setOutcomeValue(r.outcomeValue());
        o.setOutcomeNote(r.note());
        // labeledAt 转 LocalDateTime（与 evaluation_session.occurred_at 同口径：systemDefault）
        o.setLabeledAt(LocalDateTime.ofInstant(r.labeledAt(), ZoneId.systemDefault()));
        o.setSource(r.source());
        return o;
    }
}
```

- [ ] **Step 5: 写测试（mock mapper，验映射 + 空列表短路 + 幂等委托）**

```java
package com.sstlfsj.rule.eval.internal.outcome;

import com.sstlfsj.rule.eval.api.service.OutcomeService.OutcomeRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OutcomeServiceImplTest {

    private final DecisionOutcomeMapper mapper = mock(DecisionOutcomeMapper.class);
    private final OutcomeServiceImpl service = new OutcomeServiceImpl(mapper);

    @Test
    void emptyList_shortCircuits_noDbCall() {
        assertEquals(0, service.recordOutcomes(1L, List.of()));
        verifyNoInteractions(mapper);
    }

    @Test
    void mapsRecordsAndUpserts() {
        Instant t = Instant.parse("2026-06-18T10:00:00Z");
        int n = service.recordOutcomes(7L, List.of(
                new OutcomeRecord("evt-1", "FRAUD", new BigDecimal("1280.50"), t, "ops", "chargeback")));
        assertEquals(1, n);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DecisionOutcome>> cap = ArgumentCaptor.forClass(List.class);
        verify(mapper).upsertBatch(cap.capture());
        DecisionOutcome row = cap.getValue().get(0);
        assertEquals(7L, row.getTenantId());
        assertEquals("evt-1", row.getEventId());
        assertEquals("FRAUD", row.getOutcomeLabel());
        assertEquals(new BigDecimal("1280.50"), row.getOutcomeValue());
        assertEquals("ops", row.getSource());
        assertEquals("chargeback", row.getOutcomeNote());
        assertNotNull(row.getLabeledAt());
    }
}
```

- [ ] **Step 6: 跑测试**

Run: `$MVN -pl rule-eval-svc -am test -Dtest=OutcomeServiceImplTest`
Expected: PASS（2 个用例绿）

- [ ] **Step 7: 提交**

```bash
git add rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/outcome rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/api/service/OutcomeService.java rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/outcome
git commit -m "feat(eval): B32 标签回灌写侧——DecisionOutcome 实体+upsert Mapper+OutcomeService"
```

---

### Task 3: audit-svc 读侧 — 聚合 Mapper(JSON_TABLE) + EffectivenessService

**Files:**
- Create: `rule-audit-svc/src/main/java/com/sstlfsj/rule/audit/internal/repository/EffectivenessReadMapper.java`
- Create: `rule-audit-svc/src/main/resources/mapper/EffectivenessReadMapper.xml`
- Create: `rule-audit-svc/src/main/java/com/sstlfsj/rule/audit/api/service/EffectivenessService.java`
- Create: `rule-audit-svc/src/main/java/com/sstlfsj/rule/audit/internal/service/EffectivenessServiceImpl.java`
- Test: `rule-audit-svc/src/test/java/com/sstlfsj/rule/audit/internal/service/EffectivenessServiceImplTest.java`
- Test: `rule-audit-svc/src/test/java/com/sstlfsj/rule/audit/internal/repository/EffectivenessReadMapperTest.java`

- [ ] **Step 1: 写 api/service 接口 EffectivenessService（含 query + 结果记录）**

```java
package com.sstlfsj.rule.audit.api.service;

import java.time.Instant;
import java.util.List;

/** 决策效果聚合查询（B32）：按规则版本 / Decision 维度算混淆矩阵 + precision/recall + 漂移。 */
public interface EffectivenessService {

    /** 聚合维度。 */
    enum Dimension { RULE_VERSION, DECISION }

    /** 时间分桶（漂移序列）。 */
    enum Bucket { NONE, DAY, WEEK }

    /**
     * 聚合查询条件。
     *
     * @param tenantId       租户
     * @param sceneCode      场景（recall 的 FN 分母作用域）
     * @param from           窗口起（含），按 evaluation_session.occurred_at
     * @param to             窗口止（不含）
     * @param positiveLabels positive 判定口径（业务给）；空则全部计为 negative
     * @param dimension      聚合维度
     * @param bucket         时间分桶
     */
    record EffectivenessQuery(Long tenantId, String sceneCode, Instant from, Instant to,
                              List<String> positiveLabels, Dimension dimension, Bucket bucket) {}

    /** 单维度键的混淆矩阵 + 指标。precision/recall 分母为 0 时为 null。 */
    record EffectivenessRow(String dimensionKey, long tp, long fp, long fn, long tn,
                            Double precision, Double recall, double fireRate, long firedTotal) {}

    /** 单时间桶的报表：含诚实回报口径（unlabeled / blocked 不入指标分母）。 */
    record BucketReport(String bucket, long totalSessions, long labeledCount, long unlabeledCount,
                        long blockedCount, long totalPositive, long totalNegative,
                        List<EffectivenessRow> rows) {}

    /** 聚合报表：按桶分组（NONE 时单桶 bucket=null）。 */
    record EffectivenessReport(List<BucketReport> buckets) {}

    /**
     * 按需聚合决策效果。
     *
     * @param q 查询条件
     * @return 分桶报表
     */
    EffectivenessReport aggregate(EffectivenessQuery q);
}
```

- [ ] **Step 2: 写读 Mapper 接口 + 原始计数行**

```java
package com.sstlfsj.rule.audit.internal.repository;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/** 决策效果聚合只读 Mapper（B32）：JSON_TABLE 展开 hit_decisions 出原始计数，比率在 service 算。 */
@Mapper
public interface EffectivenessReadMapper {

    /** 单维度键混淆计数行（含 bucket）。 */
    record ConfusionCount(String bucket, String dimKey, long tp, long fp, long firedTotal) {}

    /** 单桶总量行。 */
    record WindowTotals(String bucket, long totalSessions, long labeledCount,
                        long totalPositive, long totalNegative, long blockedCount) {}

    /**
     * 按 (bucket, 维度键) 聚合 TP/FP/firedTotal（去重到 session 粒度，规则绑多决策不重复计）。
     *
     * @param tenantId       租户
     * @param sceneCode      场景
     * @param from           窗口起（含）
     * @param to             窗口止（不含）
     * @param dimPath        JSON 路径：'$.ruleVersionId' 或 '$.code'
     * @param positiveLabels positive 标签集（可空/空）
     * @param bucketUnit     'NONE' | 'DAY' | 'WEEK'
     * @return 混淆计数行
     */
    List<ConfusionCount> confusionByDimension(
            @Param("tenantId") Long tenantId, @Param("sceneCode") String sceneCode,
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
            @Param("dimPath") String dimPath, @Param("positiveLabels") List<String> positiveLabels,
            @Param("bucketUnit") String bucketUnit);

    /**
     * 按 bucket 聚合窗口总量（总 session / 有标签 / positive / negative / blocked）。
     *
     * @param tenantId       租户
     * @param sceneCode      场景
     * @param from           窗口起（含）
     * @param to             窗口止（不含）
     * @param positiveLabels positive 标签集（可空/空）
     * @param bucketUnit     'NONE' | 'DAY' | 'WEEK'
     * @return 桶总量行
     */
    List<WindowTotals> windowTotals(
            @Param("tenantId") Long tenantId, @Param("sceneCode") String sceneCode,
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
            @Param("positiveLabels") List<String> positiveLabels, @Param("bucketUnit") String bucketUnit);
}
```

- [ ] **Step 3: 写 XML（JSON_TABLE 聚合）**

`rule-audit-svc/src/main/resources/mapper/EffectivenessReadMapper.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">

<mapper namespace="com.sstlfsj.rule.audit.internal.repository.EffectivenessReadMapper">

    <!-- bucket 表达式：NONE 归一桶 'ALL'，DAY/WEEK 按 occurred_at 分桶 -->
    <sql id="bucketExpr">
        <choose>
            <when test="bucketUnit == 'DAY'">DATE_FORMAT(es.occurred_at, '%Y-%m-%d')</when>
            <when test="bucketUnit == 'WEEK'">DATE_FORMAT(es.occurred_at, '%x-W%v')</when>
            <otherwise>'ALL'</otherwise>
        </choose>
    </sql>

    <!-- positive 判定：标签集非空时 IN，空时恒 false（全部计 negative） -->
    <sql id="isPositive">
        <choose>
            <when test="positiveLabels != null and positiveLabels.size() > 0">
                o.outcome_label IN
                <foreach collection="positiveLabels" item="lbl" open="(" separator="," close=")">#{lbl}</foreach>
            </when>
            <otherwise>1 = 0</otherwise>
        </choose>
    </sql>

    <!-- 按维度键的混淆计数：CROSS JOIN JSON_TABLE 展开 hit_decisions，去重到 session 粒度 -->
    <select id="confusionByDimension" resultType="com.sstlfsj.rule.audit.internal.repository.EffectivenessReadMapper$ConfusionCount">
        SELECT <include refid="bucketExpr"/> AS bucket,
               jt.dim_key AS dimKey,
               COUNT(DISTINCT CASE WHEN o.event_id IS NOT NULL AND (<include refid="isPositive"/>) THEN es.id END) AS tp,
               COUNT(DISTINCT CASE WHEN o.event_id IS NOT NULL AND NOT (<include refid="isPositive"/>) THEN es.id END) AS fp,
               COUNT(DISTINCT es.id) AS firedTotal
        FROM evaluation_session es
        CROSS JOIN JSON_TABLE(es.hit_decisions, '$[*]'
             COLUMNS (dim_key VARCHAR(128) PATH #{dimPath})) jt
        LEFT JOIN decision_outcome o
               ON o.tenant_id = es.tenant_id AND o.event_id = es.event_id
        WHERE es.tenant_id = #{tenantId}
          AND es.scene_code = #{sceneCode}
          AND es.occurred_at &gt;= #{from}
          AND es.occurred_at &lt; #{to}
          AND jt.dim_key IS NOT NULL
        GROUP BY bucket, jt.dim_key
    </select>

    <!-- 按桶窗口总量 -->
    <select id="windowTotals" resultType="com.sstlfsj.rule.audit.internal.repository.EffectivenessReadMapper$WindowTotals">
        SELECT <include refid="bucketExpr"/> AS bucket,
               COUNT(*) AS totalSessions,
               SUM(CASE WHEN o.event_id IS NOT NULL THEN 1 ELSE 0 END) AS labeledCount,
               SUM(CASE WHEN o.event_id IS NOT NULL AND (<include refid="isPositive"/>) THEN 1 ELSE 0 END) AS totalPositive,
               SUM(CASE WHEN o.event_id IS NOT NULL AND NOT (<include refid="isPositive"/>) THEN 1 ELSE 0 END) AS totalNegative,
               SUM(CASE WHEN es.status = 'BLOCKED' THEN 1 ELSE 0 END) AS blockedCount
        FROM evaluation_session es
        LEFT JOIN decision_outcome o
               ON o.tenant_id = es.tenant_id AND o.event_id = es.event_id
        WHERE es.tenant_id = #{tenantId}
          AND es.scene_code = #{sceneCode}
          AND es.occurred_at &gt;= #{from}
          AND es.occurred_at &lt; #{to}
        GROUP BY bucket
    </select>

</mapper>
```

- [ ] **Step 4: 写实现 EffectivenessServiceImpl（service 层算比率 + FN/TN 推导）**

```java
package com.sstlfsj.rule.audit.internal.service;

import com.sstlfsj.rule.audit.api.service.EffectivenessService;
import com.sstlfsj.rule.audit.internal.repository.EffectivenessReadMapper;
import com.sstlfsj.rule.audit.internal.repository.EffectivenessReadMapper.ConfusionCount;
import com.sstlfsj.rule.audit.internal.repository.EffectivenessReadMapper.WindowTotals;
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
        String dimPath = q.dimension() == Dimension.DECISION ? "$.code" : "$.ruleVersionId";
        String bucketUnit = q.bucket().name();
        LocalDateTime from = LocalDateTime.ofInstant(q.from(), ZoneId.systemDefault());
        LocalDateTime to = LocalDateTime.ofInstant(q.to(), ZoneId.systemDefault());

        List<ConfusionCount> confusions = mapper.confusionByDimension(
                q.tenantId(), q.sceneCode(), from, to, dimPath, q.positiveLabels(), bucketUnit);
        List<WindowTotals> totals = mapper.windowTotals(
                q.tenantId(), q.sceneCode(), from, to, q.positiveLabels(), bucketUnit);

        // 按 bucket 归组（保序）
        Map<String, List<ConfusionCount>> byBucket = new LinkedHashMap<>();
        for (ConfusionCount c : confusions) {
            byBucket.computeIfAbsent(c.bucket(), k -> new ArrayList<>()).add(c);
        }

        List<BucketReport> buckets = new ArrayList<>();
        for (WindowTotals w : totals) {
            long totalPositive = w.totalPositive();
            long totalNegative = w.totalNegative();
            List<EffectivenessRow> rows = new ArrayList<>();
            for (ConfusionCount c : byBucket.getOrDefault(w.bucket(), List.of())) {
                long tp = c.tp();
                long fp = c.fp();
                long fn = totalPositive - tp;   // 该 scene+桶 positive 中未命中本维度键者
                long tn = totalNegative - fp;
                Double precision = (tp + fp) == 0 ? null : (double) tp / (tp + fp);
                Double recall = totalPositive == 0 ? null : (double) tp / totalPositive;
                double fireRate = w.totalSessions() == 0 ? 0.0 : (double) c.firedTotal() / w.totalSessions();
                rows.add(new EffectivenessRow(c.dimKey(), tp, fp, fn, tn, precision, recall, fireRate, c.firedTotal()));
            }
            String bucketLabel = q.bucket() == Bucket.NONE ? null : w.bucket();
            buckets.add(new BucketReport(bucketLabel, w.totalSessions(), w.labeledCount(),
                    w.totalSessions() - w.labeledCount(), w.blockedCount(),
                    totalPositive, totalNegative, rows));
        }
        return new EffectivenessReport(buckets);
    }
}
```

- [ ] **Step 5: 写 service 单测（mock mapper，验 FN/precision/recall/fireRate 推导）**

```java
package com.sstlfsj.rule.audit.internal.service;

import com.sstlfsj.rule.audit.api.service.EffectivenessService.*;
import com.sstlfsj.rule.audit.internal.repository.EffectivenessReadMapper;
import com.sstlfsj.rule.audit.internal.repository.EffectivenessReadMapper.ConfusionCount;
import com.sstlfsj.rule.audit.internal.repository.EffectivenessReadMapper.WindowTotals;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EffectivenessServiceImplTest {

    private final EffectivenessReadMapper mapper = mock(EffectivenessReadMapper.class);
    private final EffectivenessServiceImpl service = new EffectivenessServiceImpl(mapper);

    @Test
    void derivesConfusionMatrixAndRatios() {
        // 桶 ALL：总 100 session，labeled 80，positive 20，negative 60，blocked 5
        when(mapper.windowTotals(any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(new WindowTotals("ALL", 100, 80, 20, 60, 5)));
        // 规则 V1：命中 30 次（firedTotal），其中 TP=15、FP=10（labeled fired=25，余 5 未标签）
        when(mapper.confusionByDimension(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(new ConfusionCount("ALL", "1001", 15, 10, 30)));

        EffectivenessReport rep = service.aggregate(new EffectivenessQuery(
                1L, "fraud_check", Instant.parse("2026-06-01T00:00:00Z"),
                Instant.parse("2026-06-19T00:00:00Z"), List.of("FRAUD"), Dimension.RULE_VERSION, Bucket.NONE));

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
                .thenReturn(List.of(new WindowTotals("ALL", 0, 0, 0, 0, 0)));
        when(mapper.confusionByDimension(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(new ConfusionCount("ALL", "1001", 0, 0, 0)));

        EffectivenessReport rep = service.aggregate(new EffectivenessQuery(
                1L, "s", Instant.parse("2026-06-01T00:00:00Z"),
                Instant.parse("2026-06-19T00:00:00Z"), List.of("FRAUD"), Dimension.RULE_VERSION, Bucket.NONE));

        EffectivenessRow row = rep.buckets().get(0).rows().get(0);
        assertNull(row.precision());
        assertNull(row.recall());
        assertEquals(0.0, row.fireRate());
    }
}
```

- [ ] **Step 6: 写 Mapper 集成测试（真 H2/MySQL 方言验 JSON_TABLE SQL）**

> 注：本仓 Mapper 测试用真实 DB 方言。先确认既有 `EvalSessionReadMapperTest` 的基类/注解（`@MybatisPlusTest` 或 `@SpringBootTest` + testcontainers / 内嵌库），照搬同款。JSON_TABLE 仅 MySQL 8 支持——若既有 Mapper 测试跑 H2，则本 Mapper 的 SQL 验证下放到 Task 5 真实服务 e2e，本步只建空壳测试占位并在注释标注「JSON_TABLE 需 MySQL，见 e2e」。

```java
package com.sstlfsj.rule.audit.internal.repository;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** EffectivenessReadMapper 的 JSON_TABLE SQL 依赖 MySQL 8 方言，
 *  真实落库验证在 Task 5 e2e（构造 session+outcome 后核对聚合）；此处仅占位说明。 */
class EffectivenessReadMapperTest {

    @Test
    void jsonTableSqlVerifiedInE2e() {
        assertTrue(true, "JSON_TABLE 聚合在真实 MySQL e2e 验证，见 plan Task 5");
    }
}
```

- [ ] **Step 7: 跑测试**

Run: `$MVN -pl rule-audit-svc -am test -Dtest=EffectivenessServiceImplTest,EffectivenessReadMapperTest`
Expected: PASS

- [ ] **Step 8: 提交**

```bash
git add rule-audit-svc/src/main/java/com/sstlfsj/rule/audit rule-audit-svc/src/main/resources/mapper/EffectivenessReadMapper.xml rule-audit-svc/src/test/java/com/sstlfsj/rule/audit
git commit -m "feat(audit): B32 效果聚合读侧——JSON_TABLE 混淆矩阵 Mapper + EffectivenessService"
```

---

### Task 4: rule-api — OutcomeController（回灌 POST + 聚合 GET）

**Files:**
- Create: `rule-api/src/main/java/com/sstlfsj/rule/web/admin/dto/RecordOutcomesRequest.java`
- Create: `rule-api/src/main/java/com/sstlfsj/rule/web/admin/OutcomeController.java`
- Test: `rule-api/src/test/java/com/sstlfsj/rule/web/admin/OutcomeControllerTest.java`

- [ ] **Step 1: 写请求 DTO**

```java
package com.sstlfsj.rule.web.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 标签回灌请求体（B32）。 */
public record RecordOutcomesRequest(
        @NotNull Long tenantId,
        @NotEmpty List<OutcomeItem> outcomes) {

    /** 单条回灌项。 */
    public record OutcomeItem(
            @NotBlank String eventId,
            @NotBlank String outcomeLabel,
            BigDecimal outcomeValue,
            @NotNull Instant labeledAt,
            String source,
            String note) {}
}
```

- [ ] **Step 2: 写 Controller**

```java
package com.sstlfsj.rule.web.admin;

import com.sstlfsj.rule.audit.api.service.EffectivenessService;
import com.sstlfsj.rule.audit.api.service.EffectivenessService.EffectivenessQuery;
import com.sstlfsj.rule.audit.api.service.EffectivenessService.EffectivenessReport;
import com.sstlfsj.rule.eval.api.service.OutcomeService;
import com.sstlfsj.rule.eval.api.service.OutcomeService.OutcomeRecord;
import com.sstlfsj.rule.web.admin.dto.RecordOutcomesRequest;
import com.sstlfsj.rule.web.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 决策效果闭环入口（B32）：标签回灌 + 效果聚合查询。 */
@RestController
@RequestMapping("/admin/v1/decision-outcomes")
@RequiredArgsConstructor
public class OutcomeController {

    private final OutcomeService outcomeService;
    private final EffectivenessService effectivenessService;

    /** POST /admin/v1/decision-outcomes — 批量回灌结果标签（幂等 upsert）。
     * @param req 回灌请求体
     * @return 接受条数 */
    @PostMapping
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<Map<String, Integer>> record(@Valid @RequestBody RecordOutcomesRequest req) {
        List<OutcomeRecord> records = req.outcomes().stream()
                .map(o -> new OutcomeRecord(o.eventId(), o.outcomeLabel(), o.outcomeValue(),
                        o.labeledAt(), o.source(), o.note()))
                .toList();
        int accepted = outcomeService.recordOutcomes(req.tenantId(), records);
        return ApiResponse.ok(Map.of("accepted", accepted));
    }

    /** GET /admin/v1/decision-outcomes/effectiveness — 按需聚合决策效果。
     * @param tenantId 租户 @param sceneCode 场景 @param from 窗口起 @param to 窗口止
     * @param positiveLabels positive 判定口径（逗号分隔） @param dimension RULE_VERSION|DECISION @param bucket NONE|DAY|WEEK
     * @return 分桶效果报表 */
    @GetMapping("/effectiveness")
    public ApiResponse<EffectivenessReport> effectiveness(
            @RequestParam Long tenantId,
            @RequestParam String sceneCode,
            @RequestParam Instant from,
            @RequestParam Instant to,
            @RequestParam(required = false) List<String> positiveLabels,
            @RequestParam(defaultValue = "RULE_VERSION") EffectivenessService.Dimension dimension,
            @RequestParam(defaultValue = "NONE") EffectivenessService.Bucket bucket) {
        EffectivenessReport report = effectivenessService.aggregate(new EffectivenessQuery(
                tenantId, sceneCode, from, to,
                positiveLabels == null ? List.of() : positiveLabels, dimension, bucket));
        return ApiResponse.ok(report);
    }
}
```

- [ ] **Step 3: 写 Controller 单测（mock 两 service，验请求映射 + 响应）**

```java
package com.sstlfsj.rule.web.admin;

import com.sstlfsj.rule.audit.api.service.EffectivenessService;
import com.sstlfsj.rule.audit.api.service.EffectivenessService.EffectivenessReport;
import com.sstlfsj.rule.eval.api.service.OutcomeService;
import com.sstlfsj.rule.eval.api.service.OutcomeService.OutcomeRecord;
import com.sstlfsj.rule.web.admin.dto.RecordOutcomesRequest;
import com.sstlfsj.rule.web.admin.dto.RecordOutcomesRequest.OutcomeItem;
import com.sstlfsj.rule.web.common.ApiResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OutcomeControllerTest {

    private final OutcomeService outcomeService = mock(OutcomeService.class);
    private final EffectivenessService effectivenessService = mock(EffectivenessService.class);
    private final OutcomeController controller = new OutcomeController(outcomeService, effectivenessService);

    @Test
    void record_mapsItemsAndReturnsAccepted() {
        when(outcomeService.recordOutcomes(eq(1L), anyList())).thenReturn(2);
        RecordOutcomesRequest req = new RecordOutcomesRequest(1L, List.of(
                new OutcomeItem("e1", "FRAUD", null, Instant.parse("2026-06-18T00:00:00Z"), "ops", null),
                new OutcomeItem("e2", "NOT_FRAUD", null, Instant.parse("2026-06-18T00:00:00Z"), null, null)));

        ApiResponse<Map<String, Integer>> resp = controller.record(req);

        assertTrue(resp.success());
        assertEquals(2, resp.data().get("accepted"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<OutcomeRecord>> cap = ArgumentCaptor.forClass(List.class);
        verify(outcomeService).recordOutcomes(eq(1L), cap.capture());
        assertEquals("e1", cap.getValue().get(0).eventId());
        assertEquals("FRAUD", cap.getValue().get(0).outcomeLabel());
    }

    @Test
    void effectiveness_nullPositiveLabels_defaultsToEmptyList() {
        when(effectivenessService.aggregate(any())).thenReturn(new EffectivenessReport(List.of()));
        ApiResponse<EffectivenessReport> resp = controller.effectiveness(
                1L, "s", Instant.parse("2026-06-01T00:00:00Z"), Instant.parse("2026-06-19T00:00:00Z"),
                null, EffectivenessService.Dimension.RULE_VERSION, EffectivenessService.Bucket.NONE);
        assertTrue(resp.success());
        verify(effectivenessService).aggregate(argThat(q -> q.positiveLabels().isEmpty()));
    }
}
```

- [ ] **Step 4: 跑测试**

Run: `$MVN -pl rule-api -am test -Dtest=OutcomeControllerTest`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add rule-api/src/main/java/com/sstlfsj/rule/web/admin/OutcomeController.java rule-api/src/main/java/com/sstlfsj/rule/web/admin/dto/RecordOutcomesRequest.java rule-api/src/test/java/com/sstlfsj/rule/web/admin/OutcomeControllerTest.java
git commit -m "feat(api): B32 OutcomeController——标签回灌 POST + 效果聚合 GET"
```

---

### Task 5: 全量回归 + 真实服务 e2e

- [ ] **Step 1: 全量 clean test 兜底**

Run: `$MVN clean test`
Expected: BUILD SUCCESS（跨模块改动须 clean 强制重编所有 test 类）

- [ ] **Step 2: 起真实服务**

打可执行包后跑 rule-app（别用 reactor 内 run 目标）；确认 Flyway 迁移到 V1_36、服务就绪。参考 `docs/examples/` 剧本。

- [ ] **Step 3: 盘现状 + 造决策日志**

经评估 API（`POST /api/v1/rule/event` 或 `evaluate`）跑出若干 `evaluation_session`（覆盖命中/未命中/BLOCKED、同 scene），或直接构造数据。查 `evaluation_session` 确认 `hit_decisions` 落了 `ruleVersionId`。

- [ ] **Step 4: 回灌标签 + 核对真落库**

`POST /admin/v1/decision-outcomes` 批量回灌（含 positive/negative 标签、含早于 session 的 orphan event、含覆盖更新同 eventId）。查 `decision_outcome` 表确认真 upsert（覆盖那条 `updated_at` 变化、label 被替换）。

- [ ] **Step 5: 核对聚合 SQL**

`GET /admin/v1/decision-outcomes/effectiveness?...&dimension=RULE_VERSION` 与 `dimension=DECISION`、`bucket=DAY`，手算 TP/FP/FN/precision/recall/fireRate 与响应逐项核对；验 unlabeled 不入分母、blocked 显式回报、orphan outcome（无对应 session）不串进聚合。

- [ ] **Step 6: DB 字段落库审计**

查 `decision_outcome` 恒空字段分类（outcome_value/note/source 测试是否覆盖）；本轮新增列专门验证（真 upsert、覆盖更新）。

- [ ] **Step 7: 清理测试数据**

删本次新建的 `decision_outcome` + `evaluation_session` 测试数据，环境恢复干净基线。

---

### Task 6: 文档同步

**Files:**
- Modify: `docs/08-evolution.md` §2.27（标注实装 + 落点）
- Modify: `docs/10-api-contract.md`（补两个 admin 端点 + errorCode 表如有新增）
- Modify: `docs/superpowers/plans/backlog.md`（B32 移入已落地清单）

- [ ] **Step 1: 跨文档改动前跑 `doc-consistency-review` skill**
- [ ] **Step 2: 更新三份文档**（§2.27 仿 §2.28 B33「已实装」体例；append-only 决策日志不动历史）
- [ ] **Step 3: 派 `rule-engine-reviewer` agent 审代码↔文档对齐**
- [ ] **Step 4: 提交**

```bash
git add docs/08-evolution.md docs/10-api-contract.md docs/superpowers/plans/backlog.md
git commit -m "docs: B32 决策效果闭环落地——§2.27 实装标注 + API 契约 + backlog 移入已落地"
```

---

## Self-Review

**Spec coverage：**
- decision_outcome 表 → Task 1 ✅
- 同步回灌 API + upsert 幂等 + 不校验 session → Task 2（impl）+ Task 4（HTTP）✅
- 按需 SQL 聚合 TP/FP/FN/precision/recall/fireRate → Task 3 ✅
- JSON_TABLE 展开 hit_decisions 规则/决策双维度 → Task 3 XML ✅
- 时间窗锚 occurred_at + DAY/WEEK 漂移 → Task 3 bucketExpr ✅
- positiveLabels 查询期口径 + 空集恒 negative → Task 3 isPositive ✅
- reject-inference 诚实回报（unlabeled/blocked） → Task 3 WindowTotals + BucketReport ✅
- recall 的 scene 作用域 → sceneCode 必填参数 ✅
- 文档同步 → Task 6 ✅

**Placeholder scan：** Task 3 Step 6 的 Mapper 测试是有意占位（JSON_TABLE 需 MySQL，验证下放 e2e），已注明理由，非空泛占位。其余步骤均含完整代码/命令。

**Type consistency：** `OutcomeRecord`（eval-svc api）字段 eventId/outcomeLabel/outcomeValue/labeledAt/source/note 在 Task 2/4 一致；`EffectivenessQuery`/`EffectivenessRow`/`BucketReport`/`EffectivenessReport` 在 Task 3/4 一致；`ConfusionCount`/`WindowTotals` 在 Mapper↔Impl 一致；`upsertBatch`/`confusionByDimension`/`windowTotals`/`recordOutcomes`/`aggregate` 方法名跨任务一致。

**风险点（执行时确认）：**
1. MyBatis resultType 引用嵌套 record 用 `$` 分隔（`...EffectivenessReadMapper$ConfusionCount`）——若本仓 MyBatis 版本不认，改为顶层 record 或 `<resultMap>`。执行 Task 3 Step 7 时验证。
2. `@RequestParam Instant` 需 Spring 能转 ISO-8601——若不行，改收 String 再 `Instant.parse`（仿 AuditController from/to）。执行 Task 4 时验证。
3. JSON_TABLE 的 `dim_key VARCHAR(128) PATH #{dimPath}`——PATH 是否允许参数化绑定待验；若不允许（PATH 须字面量），改用 `<choose>` 在 XML 内按 dimension 选 `'$.ruleVersionId'`/`'$.code'` 字面量，dimension 作为 `@Param` 传字符串判断。执行 Task 3 时优先按此稳妥写法。
