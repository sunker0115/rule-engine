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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 合成执行器:把 @Decide 方法的返回决策码翻译成 EvalResult.hitDecisions。
 * 按快照 conditionAst 携带的坐标键反查方法;返回码须 ⊆ 快照 decisionBindings,非法码丢弃 + errorCode。
 */
public final class AnnotatedDecideExecutor implements RuleVersionExecutor {

    /** 一条 @Decide 规则的调用三元组。 */
    public record Invocation(Object bean, Method method, FactResolver factResolver) {}

    private static final Comparator<Decision> BY_PRIORITY = Comparator.comparingInt(Decision::priority);

    private final Map<String, Invocation> byKey;

    public AnnotatedDecideExecutor(Map<String, Invocation> byKey) {
        this.byKey = Map.copyOf(byKey);
    }

    @Override
    public EvalResult execute(RuleVersionSnapshot snapshot, EvalContext ctx) {
        String key = ((ConditionNode) snapshot.conditionAst()).conditionType();
        Invocation inv = byKey.get(key);
        if (inv == null) return EvalResult.error(EvalErrorCode.ANNO_DECIDE_UNREGISTERED);

        List<String> codes;
        try {
            inv.method().setAccessible(true);
            Object[] args = inv.factResolver().resolve(inv.method().getParameters(), ctx, null);
            codes = toCodes(inv.method().invoke(inv.bean(), args));
        } catch (Exception e) {
            return EvalResult.error(EvalErrorCode.DECIDE_EVAL_ERROR);
        }
        if (codes.isEmpty()) return EvalResult.miss();

        List<Decision> hits = new ArrayList<>();
        EvalErrorCode errorCode = null;
        for (String code : codes) {
            RuleVersionSnapshot.DecisionBinding b = findBinding(snapshot, code);
            if (b == null) { errorCode = EvalErrorCode.INVALID_DECISION_CODE; continue; }
            hits.add(new Decision(b.decisionCode(), b.name(), b.priority(),
                    snapshot.ruleVersionId(), snapshot.code(), snapshot.version(), null));
        }
        if (hits.isEmpty()) {
            return EvalResult.error(errorCode == null ? EvalErrorCode.ANNO_DECIDE_NO_HIT : errorCode);
        }
        Decision finalD = Collections.max(hits, BY_PRIORITY);
        // EvalResult.errorCode 字段为 String,enum 转回 name() 保持落库值不变
        return new EvalResult(true, finalD, hits, List.of(), errorCode == null ? null : errorCode.name(), null, finalD.category(), finalD.code());
    }

    private static List<String> toCodes(Object ret) {
        if (ret == null) return List.of();
        if (ret instanceof String s) return s.isBlank() ? List.of() : List.of(s);
        if (ret instanceof String[] arr) {
            List<String> out = new ArrayList<>();
            for (String s : arr) if (s != null && !s.isBlank()) out.add(s);
            return out;
        }
        if (ret instanceof java.util.Collection<?> col) {
            List<String> out = new ArrayList<>();
            for (Object o : col) if (o != null && !o.toString().isBlank()) out.add(o.toString());
            return out;
        }
        throw new IllegalStateException("@Decide 返回类型须是 String / String[] / Collection<String>: " + ret.getClass());
    }

    private static RuleVersionSnapshot.DecisionBinding findBinding(RuleVersionSnapshot snap, String code) {
        for (RuleVersionSnapshot.DecisionBinding b : snap.decisionBindings()) {
            if (b.decisionCode().equals(code)) return b;
        }
        return null;
    }
}
