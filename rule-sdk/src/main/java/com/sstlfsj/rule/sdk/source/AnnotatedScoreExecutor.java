package com.sstlfsj.rule.sdk.source;

import com.sstlfsj.rule.kernel.api.model.Decision;
import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.EvalErrorCode;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.spi.executor.RuleVersionExecutor;
import com.sstlfsj.rule.sdk.FactResolver;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

/**
 * 合成执行器:调 @Score 方法得分,按 @ScoreBand 分档(min 最大且 ≤ score 的一档)映射决策,
 * 并把分写入 EvalResult.score。无档命中=miss。
 */
public final class AnnotatedScoreExecutor implements RuleVersionExecutor {

    /** 单个评分分档。 */
    public record Band(double min, String decision) {}
    /** 一条 @Score 规则的调用信息(方法 + 分档表)。 */
    public record Invocation(Object bean, Method method, FactResolver factResolver, List<Band> bands) {}

    private final Map<String, Invocation> byKey;

    public AnnotatedScoreExecutor(Map<String, Invocation> byKey) {
        this.byKey = Map.copyOf(byKey);
    }

    @Override
    public EvalResult execute(RuleVersionSnapshot snapshot, EvalContext ctx) {
        String key = ((ConditionNode) ((com.sstlfsj.rule.kernel.api.model.AstBody) snapshot.body()).conditionAst()).conditionType();
        Invocation inv = byKey.get(key);
        if (inv == null) return EvalResult.error(EvalErrorCode.ANNO_SCORE_UNREGISTERED);

        double score;
        try {
            inv.method().setAccessible(true);
            Object[] args = inv.factResolver().resolve(inv.method().getParameters(), ctx, null);
            Object ret = inv.method().invoke(inv.bean(), args);
            score = ((Number) ret).doubleValue();
        } catch (Exception e) {
            return EvalResult.error(EvalErrorCode.SCORE_EVAL_ERROR);
        }

        Band best = null;
        for (Band b : inv.bands()) {
            if (score >= b.min() && (best == null || b.min() > best.min())) best = b;
        }
        if (best == null) {
            return new EvalResult(false, null, List.of(), List.of(), null, score, null, null);
        }
        RuleVersionSnapshot.DecisionBinding bind = findBinding(snapshot, best.decision());
        if (bind == null) {
            return new EvalResult(false, null, List.of(), List.of(), EvalErrorCode.INVALID_DECISION_CODE.name(), score, null, null);
        }
        Decision d = new Decision(bind.decisionCode(), bind.name(), bind.priority(),
                snapshot.ruleVersionId(), snapshot.code(), snapshot.version(), null);
        return new EvalResult(true, d, List.of(d), List.of(), null, score, d.category(), d.code());
    }

    private static RuleVersionSnapshot.DecisionBinding findBinding(RuleVersionSnapshot snap, String code) {
        for (RuleVersionSnapshot.DecisionBinding b : snap.decisionBindings()) {
            if (b.decisionCode().equals(code)) return b;
        }
        return null;
    }
}
