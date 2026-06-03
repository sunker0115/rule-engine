package com.sstlfsj.rule.eval.internal.pregate;

import com.google.common.hash.Hashing;
import com.sstlfsj.rule.kernel.api.model.PreGateContext;
import com.sstlfsj.rule.kernel.api.model.PreGateResult;
import com.sstlfsj.rule.kernel.api.spi.pregate.PreGate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * ROLLOUT Pre-Gate：按 murmur3_32(subjectId:ruleVersionId) % 100 < percentage 决定是否放行。
 * 同一 subjectId + ruleVersionId 组合结果确定；不同 ruleVersionId 互相独立分桶。
 * 缺少 percentage 配置时 fail-open（视为全量放行）。
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

        // murmur3_32(subjectId:ruleVersionId) 确保不同规则版本独立分桶
        // 用 & 0x7fffffff 屏蔽符号位，避免 Integer.MIN_VALUE 取绝对值仍为负数的 JVM 陷阱
        String hashInput = ctx.subjectId() + ":" + ctx.ruleVersionId();
        int bucket = (Hashing.murmur3_32_fixed()
                .hashString(hashInput, StandardCharsets.UTF_8)
                .asInt() & 0x7fffffff) % 100;

        return bucket < percentage ? PreGateResult.pass() : PreGateResult.blocked("ROLLOUT");
    }
}
