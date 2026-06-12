package com.sstlfsj.rule.expression.cel;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.sstlfsj.rule.kernel.api.model.ExpressionLang;
import com.sstlfsj.rule.kernel.api.spi.expression.CompiledExpression;
import com.sstlfsj.rule.kernel.api.spi.expression.ExpressionCompileException;
import com.sstlfsj.rule.kernel.api.spi.expression.ExpressionEngine;
import com.sstlfsj.rule.kernel.api.spi.expression.ExpressionEvaluateException;
import com.google.protobuf.Timestamp;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelValidationException;
import dev.cel.common.CelValidationResult;
import dev.cel.common.types.MapType;
import dev.cel.common.types.SimpleType;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelRuntime;
import dev.cel.runtime.CelRuntimeFactory;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * dev.cel 实现的运行期表达式引擎(EXPRESSION_SCRIPT 默认引擎)。
 * dyn env(metrics/payload/subject = map(string,dyn)、now = timestamp),scene 无关、线程安全单例。
 * 按源码内容缓存编译产物(Caffeine);类型检查在发布期(config-svc)另做,本引擎只 compile+eval。
 */
public final class CelExpressionEngine implements ExpressionEngine {

    private final CelCompiler compiler;
    private final CelRuntime runtime;
    private final Cache<String, CelCompiledExpression> cache;

    /** 默认缓存上限 10_000(脚本规则数量级远小于此)。 */
    public CelExpressionEngine() {
        this(10_000);
    }

    /**
     * @param maxCachedExpressions 预编译缓存上限
     */
    public CelExpressionEngine(long maxCachedExpressions) {
        this.compiler = CelCompilerFactory.standardCelCompilerBuilder()
                .addVar("metrics", MapType.create(SimpleType.STRING, SimpleType.DYN))
                .addVar("payload", MapType.create(SimpleType.STRING, SimpleType.DYN))
                .addVar("subject", MapType.create(SimpleType.STRING, SimpleType.DYN))
                .addVar("now", SimpleType.TIMESTAMP)
                .build();
        this.runtime = CelRuntimeFactory.standardCelRuntimeBuilder().build();
        this.cache = Caffeine.newBuilder().maximumSize(maxCachedExpressions).build();
    }

    @Override
    public String lang() {
        return ExpressionLang.CEL.tag();
    }

    @Override
    public CompiledExpression compile(String source) {
        // 内容寻址缓存:同源脚本(跨规则/版本)共享一份编译产物
        return cache.get(source, this::doCompile);
    }

    private CelCompiledExpression doCompile(String source) {
        CelValidationResult result = compiler.compile(source);
        if (result.hasError()) {
            throw new ExpressionCompileException("CEL 编译失败: " + result.getErrorString());
        }
        try {
            CelAbstractSyntaxTree ast = result.getAst();
            return new CelCompiledExpression(ast, CelReferencedVariables.from(ast));
        } catch (CelValidationException e) {
            throw new ExpressionCompileException("CEL 编译失败: " + e.getMessage(), e);
        }
    }

    @Override
    public Object evaluate(CompiledExpression compiled, Map<String, Object> bindings) {
        CelCompiledExpression cel = (CelCompiledExpression) compiled;
        try {
            // 运行期对 dyn env 求值;ScriptExecutor 捕获异常转 SCRIPT_EVAL_ERROR
            return runtime.createProgram(cel.ast()).eval(adaptBindings(bindings));
        } catch (CelEvaluationException e) {
            throw new ExpressionEvaluateException("CEL 求值失败: " + e.getMessage(), e);
        }
    }

    /**
     * dev.cel TIMESTAMP 运行期表示为 protobuf {@link Timestamp},不直接吃 {@link Instant};
     * 把绑定面 now(Instant)转成 protobuf Timestamp,其余键原样透传。
     */
    private static Map<String, Object> adaptBindings(Map<String, Object> bindings) {
        Object now = bindings.get("now");
        if (!(now instanceof Instant instant)) {
            return bindings;
        }
        Map<String, Object> adapted = new HashMap<>(bindings);
        adapted.put("now", Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build());
        return adapted;
    }
}
