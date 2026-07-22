package com.sstlfsj.rule.kernel.internal.evaluator;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.AstBody;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.spi.executor.RuleVersionExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.function.Predicate;

/**
 * 编译执行器：把 AST_BOOLEAN 规则编译为 {@link Predicate} 闭包并缓存，替换解释器树遍历。
 * 仅服务非 trace 布尔快路径；开 trace、灰度未命中、或关开关时委托内部 {@link InterpretedExecutor}。
 * 缓存键为不可变 ruleVersionId，陈旧条目永不会错(失效仅为内存卫生，见 {@link RuleVersionCache})。
 */
public final class CompiledExecutor implements RuleVersionExecutor {

    private static final Logger log = LoggerFactory.getLogger(CompiledExecutor.class);
    /** 回落哨兵：标记某 ruleVersionId 编译失败、永久走解释器(按引用比较，从不调用)。 */
    private static final Predicate<EvalContext> FALLBACK = ctx -> { throw new AssertionError("哨兵不应被调用"); };

    private final InterpretedExecutor interpreter;
    private final AstCompiler compiler;
    private final RuleVersionCache cache;
    private final boolean enabled;
    private final Set<String> whitelist;
    private final CompileErrorPolicy onCompileError;

    /**
     * @param interpreter    回落用解释器(trace/灰度未命中/关开关时委托)
     * @param compiler       AST 编译器
     * @param cache          编译产物缓存
     * @param enabled        是否启用编译执行器
     * @param whitelist      编译白名单(规则 code)；空=全量编译
     * @param onCompileError 编译失败处置策略
     */
    public CompiledExecutor(InterpretedExecutor interpreter, AstCompiler compiler, RuleVersionCache cache,
                            boolean enabled, Set<String> whitelist, CompileErrorPolicy onCompileError) {
        this.interpreter = interpreter;
        this.compiler = compiler;
        this.cache = cache;
        this.enabled = enabled;
        this.whitelist = Set.copyOf(whitelist);
        this.onCompileError = onCompileError;
    }

    @Override
    public EvalResult execute(RuleVersionSnapshot snapshot, EvalContext ctx) {
        // 关开关 / 灰度未命中 / 开 trace：与今天逐字节一致，委托解释器
        if (!enabled
                || (!whitelist.isEmpty() && !whitelist.contains(snapshot.code()))
                || TraceScope.COLLECT.orElse(true)) {
            return interpreter.execute(snapshot, ctx);
        }
        Predicate<EvalContext> p = obtain(snapshot);
        if (p == FALLBACK) return interpreter.execute(snapshot, ctx);
        return p.test(ctx) ? EvalResult.hit() : EvalResult.miss();
    }

    /** 取缓存或惰性编译；编译失败按策略 FAIL 抛出 / FALLBACK 记哨兵回落。 */
    private Predicate<EvalContext> obtain(RuleVersionSnapshot snapshot) {
        long id = snapshot.ruleVersionId();
        Predicate<EvalContext> p = cache.get(id);
        if (p != null) return p;
        try {
            p = compiler.compile(((AstBody) snapshot.body()).conditionAst());
        } catch (RuntimeException e) {
            if (onCompileError == CompileErrorPolicy.FAIL) {
                throw new IllegalStateException(
                        "AST 编译失败 ruleVersionId=" + id + " code=" + snapshot.code(), e);
            }
            log.warn("AST 编译失败，回落解释器 ruleVersionId={} code={}", id, snapshot.code(), e);
            cache.putIfAbsent(id, FALLBACK);
            return FALLBACK;
        }
        cache.putIfAbsent(id, p);
        // 并发下另一线程可能先放入，统一返回缓存内实例
        return cache.get(id);
    }
}
