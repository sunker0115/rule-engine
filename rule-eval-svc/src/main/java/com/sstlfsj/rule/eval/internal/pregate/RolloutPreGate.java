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
 * <p>缺少 percentage 配置时 fail-open（视为全量放行）。
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
        if (percentageParam == null) {
            // 无配置时 fail-open
            return PreGateResult.pass();
        }
        int percentage = Integer.parseInt(percentageParam.toString());
        if (percentage >= 100) return PreGateResult.pass();
        if (percentage <= 0)   return PreGateResult.blocked("ROLLOUT");

        // experimentId 存在时共享种子，保证同实验 A/B 互斥；否则退回 ruleVersionId 独立分桶
        Object experimentId = ctx.gateParams().get("experimentId");
        String hashInput = experimentId != null
                ? ctx.subjectId() + ":" + experimentId
                : ctx.subjectId() + ":" + ctx.ruleVersionId();

        // & 0x7fffffff 屏蔽符号位，避免 Integer.MIN_VALUE 取绝对值仍为负数的 JVM 陷阱
        int bucket = (Hashing.murmur3_32_fixed()
                .hashString(hashInput, StandardCharsets.UTF_8)
                .asInt() & 0x7fffffff) % 100;

        return bucket < percentage ? PreGateResult.pass() : PreGateResult.blocked("ROLLOUT");
    }
}
