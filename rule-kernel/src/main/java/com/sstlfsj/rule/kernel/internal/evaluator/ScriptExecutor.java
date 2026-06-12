package com.sstlfsj.rule.kernel.internal.evaluator;

import com.sstlfsj.rule.kernel.api.model.Decision;
import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.EvalErrorCode;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.NodeTrace;
import com.sstlfsj.rule.kernel.api.model.NodeType;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ScriptSource;
import com.sstlfsj.rule.kernel.api.spi.executor.RuleVersionExecutor;
import com.sstlfsj.rule.kernel.api.spi.expression.CompiledExpression;
import com.sstlfsj.rule.kernel.api.spi.expression.ExpressionEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * EXPRESSION_SCRIPT executor:把脚本源码交给按 lang 路由的 {@link ExpressionEngine} 求值,
 * 按返回值运行时类型派发(复用 D64):Boolean→ruleHit、String→决策码、Number→score。
 * 脚本不是 AST,trace 为单节点扁平 SCRIPT(记输出值),受 {@link TraceScope#COLLECT} 守卫。
 */
public class ScriptExecutor implements RuleVersionExecutor {

    private static final Logger log = LoggerFactory.getLogger(ScriptExecutor.class);

    private final Map<String, ExpressionEngine> engines;

    /**
     * @param engines lang 到 ExpressionEngine 的映射(空 map = 未 opt-in 任何引擎)
     */
    public ScriptExecutor(Map<String, ExpressionEngine> engines) {
        this.engines = Map.copyOf(engines);
    }

    @Override
    public EvalResult execute(RuleVersionSnapshot snapshot, EvalContext ctx) {
        ScriptSource script = snapshot.script();
        if (script == null) {
            return EvalResult.error(EvalErrorCode.SCRIPT_SOURCE_MISSING);
        }
        ExpressionEngine engine = engines.get(script.lang());
        if (engine == null) {
            return EvalResult.error(EvalErrorCode.SCRIPT_NO_ENGINE);
        }

        Object result;
        try {
            CompiledExpression compiled = engine.compile(script.source());
            result = engine.evaluate(compiled, ScriptBindings.from(ctx));
        } catch (Exception e) {
            return EvalResult.error(EvalErrorCode.SCRIPT_EVAL_ERROR, scriptTrace(false, "ERROR", snapshot));
        }

        return switch (result) {
            case null -> EvalResult.miss(scriptTrace(false, null, snapshot));
            case Boolean b -> new EvalResult(b, null, List.of(), scriptTrace(b, b, snapshot),
                    null, null, null, null);
            case String code -> dispatchDecision(code, snapshot);
            case Number n -> new EvalResult(false, null, List.of(), scriptTrace(false, n, snapshot),
                    null, n.doubleValue(), null, null);
            default -> EvalResult.error(EvalErrorCode.SCRIPT_EVAL_ERROR, scriptTrace(false, "TYPE", snapshot));
        };
    }

    /**
     * 预编译给定快照中的脚本规则,把编译产物预热进各引擎缓存(加载期预热模式调用)。
     * 仅处理 script 非空的快照(非脚本规则 script 为 null,跳过);lang 无对应引擎或编译失败时
     * 记 warn 跳过,不向外抛——预热失败不应拖垮索引加载,运行期评估会按需重试并暴露错误码。
     *
     * @param snapshots 待预热的规则版本快照
     */
    public void warmUp(Collection<RuleVersionSnapshot> snapshots) {
        for (RuleVersionSnapshot snapshot : snapshots) {
            ScriptSource script = snapshot.script();
            if (script == null) {
                continue;
            }
            ExpressionEngine engine = engines.get(script.lang());
            if (engine == null) {
                log.warn("脚本规则预热跳过:无 lang={} 引擎, ruleVersionId={}", script.lang(), snapshot.ruleVersionId());
                continue;
            }
            try {
                engine.compile(script.source());
            } catch (Exception e) {
                log.warn("脚本规则预热编译失败, ruleVersionId={}: {}", snapshot.ruleVersionId(), e.getMessage());
            }
        }
    }

    /** String 返回:决策码须 ∈ decisionBindings,否则 INVALID_DECISION_CODE。 */
    private EvalResult dispatchDecision(String code, RuleVersionSnapshot snapshot) {
        RuleVersionSnapshot.DecisionBinding binding = snapshot.decisionBindings().stream()
                .filter(b -> b.decisionCode().equals(code))
                .findFirst()
                .orElse(null);
        if (binding == null) {
            return EvalResult.error(EvalErrorCode.INVALID_DECISION_CODE, scriptTrace(false, code, snapshot));
        }
        Decision d = new Decision(binding.decisionCode(), binding.name(), binding.priority(),
                snapshot.ruleVersionId(), snapshot.code(), snapshot.version(), null);
        return new EvalResult(true, d, List.of(d), scriptTrace(true, code, snapshot),
                null, null, d.category(), d.code());
    }

    /** 单节点扁平 SCRIPT trace(actualValue=脚本输出);非收集模式返回空列表(零分配契约)。 */
    private static List<NodeTrace> scriptTrace(boolean result, Object output, RuleVersionSnapshot snapshot) {
        if (!TraceScope.COLLECT.orElse(true)) {
            return List.of();
        }
        return List.of(new NodeTrace(
                NodeType.SCRIPT.tag(), null, null, result, output, null, null, List.of(),
                snapshot.ruleVersionId(), snapshot.code(), snapshot.version(), null, null));
    }
}
