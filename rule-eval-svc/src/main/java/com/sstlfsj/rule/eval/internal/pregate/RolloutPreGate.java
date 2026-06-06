package com.sstlfsj.rule.eval.internal.pregate;

import com.google.common.hash.Hashing;
import com.sstlfsj.rule.kernel.api.model.PreGateContext;
import com.sstlfsj.rule.kernel.api.model.PreGateResult;
import com.sstlfsj.rule.kernel.api.spi.pregate.PreGate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * ROLLOUT Pre-Gate：按百分比灰度放行。
 *
 * <p>hash 种子规则：
 * <ul>
 *   <li>gateParams 含 {@code experimentId} 时：{@code hash(subjectId:experimentId)} —— 同实验内
 *       多规则共享分桶，保证 A/B 互斥（同一 subject 在同实验的不同规则版本结果一致）。</li>
 *   <li>不含 {@code experimentId} 时：{@code hash(subjectId:ruleVersionId)} —— 各规则版本独立分桶，
 *       与 v1 行为完全一致。</li>
 * </ul>
 *
 * <p>命中模式（二选一，桶区间优先）：
 * <ul>
 *   <li>桶区间：配置 {@code bucketStart}/{@code bucketEnd} 时，{@code bucketStart <= bucket < bucketEnd} 命中。
 *       配合同一 {@code experimentId} 给多条规则不相交区间，即实现 A/B 互斥（每 subject 恰好命中其一）。</li>
 *   <li>百分比：仅配置 {@code percentage} 时，{@code bucket < percentage} 命中，等价于区间 {@code [0, percentage)}。</li>
 * </ul>
 * <p>percentage 与桶区间均未配置时 fail-open（全量放行）。
 */
@Component
public class RolloutPreGate implements PreGate {

    @Override
    public String gateType() {
        return "ROLLOUT";
    }

    @Override
    public PreGateResult evaluate(PreGateContext ctx) {
        Object percentageParam = ctx.gateParams().get("percentage");
        Object startParam = ctx.gateParams().get("bucketStart");
        Object endParam = ctx.gateParams().get("bucketEnd");

        // percentage 与桶区间都未配置时 fail-open（视为无灰度限制，全量放行）
        if (percentageParam == null && startParam == null && endParam == null) {
            return PreGateResult.pass();
        }

        // experimentId 存在时共享种子，保证同实验 A/B 互斥；否则退回 ruleVersionId 独立分桶
        Object experimentId = ctx.gateParams().get("experimentId");
        String hashInput = experimentId != null
                ? ctx.subjectId() + ":" + experimentId
                : ctx.subjectId() + ":" + ctx.ruleVersionId();
        // & 0x7fffffff 屏蔽符号位，避免 Integer.MIN_VALUE 取绝对值仍为负数的 JVM 陷阱
        int bucket = (Hashing.murmur3_32_fixed()
                .hashString(hashInput, StandardCharsets.UTF_8)
                .asInt() & 0x7fffffff) % 100;

        // 桶区间模式（A/B 互斥）：bucketStart <= bucket < bucketEnd；优先于 percentage
        if (startParam != null && endParam != null) {
            int start = Integer.parseInt(startParam.toString());
            int end = Integer.parseInt(endParam.toString());
            return (bucket >= start && bucket < end)
                    ? PreGateResult.pass() : PreGateResult.blocked("ROLLOUT");
        }

        // percentage 模式（语义等价于区间 [0, percentage)）
        int percentage = Integer.parseInt(percentageParam.toString());
        if (percentage >= 100) return PreGateResult.pass();
        if (percentage <= 0)   return PreGateResult.blocked("ROLLOUT");
        return bucket < percentage ? PreGateResult.pass() : PreGateResult.blocked("ROLLOUT");
    }
}
