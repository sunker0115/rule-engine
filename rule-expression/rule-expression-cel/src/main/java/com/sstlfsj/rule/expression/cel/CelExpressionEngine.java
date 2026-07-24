package com.sstlfsj.rule.expression.cel;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.sstlfsj.rule.kernel.api.model.DataType;
import com.sstlfsj.rule.kernel.api.model.ExpressionLang;
import com.sstlfsj.rule.kernel.api.spi.expression.CompiledExpression;
import com.sstlfsj.rule.kernel.api.spi.expression.ExpressionCompileException;
import com.sstlfsj.rule.kernel.api.spi.expression.ExpressionEngine;
import com.sstlfsj.rule.kernel.api.spi.expression.ExpressionEvaluateException;
import com.sstlfsj.rule.kernel.api.spi.expression.ScriptTypeEnv;
import com.google.protobuf.Timestamp;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelValidationException;
import dev.cel.common.CelValidationResult;
import dev.cel.common.types.CelType;
import dev.cel.common.types.MapType;
import dev.cel.common.types.SimpleType;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerBuilder;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelRuntime;
import dev.cel.runtime.CelRuntimeFactory;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * dev.cel 实现的运行期表达式引擎(EXPRESSION_SCRIPT 默认引擎)。
 * dyn env(metrics/payload/subject/params = map(string,dyn)、now = timestamp),scene 无关、线程安全单例。
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
                .addVar("params", MapType.create(SimpleType.STRING, SimpleType.DYN))
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
     * 发布期类型检查:按被引用变量的声明类型构造 typed env(k8s 风格:每个 {@code ns.field} 声明为
     * 带类型的点号变量),编译校验源码,只 check 不 eval。捕获 string 字段参与数值比较等类型不符。
     * {@code subject.*} 声明为 {@code map(string,dyn)}(运行期动态,不做字段级检查)。
     */
    @Override
    public void typeCheck(String source, ScriptTypeEnv typeEnv) {
        CelCompilerBuilder builder = CelCompilerFactory.standardCelCompilerBuilder()
                .addVar("subject", MapType.create(SimpleType.STRING, SimpleType.DYN))
                .addVar("params", MapType.create(SimpleType.STRING, SimpleType.DYN))
                .addVar("now", SimpleType.TIMESTAMP);
        typeEnv.metrics().forEach((code, dt) -> builder.addVar("metrics." + code, celType(dt)));
        typeEnv.payload().forEach((field, dt) -> builder.addVar("payload." + field, celType(dt)));
        CelValidationResult result = builder.build().compile(source);
        if (result.hasError()) {
            throw new ExpressionCompileException("CEL 类型检查失败: " + result.getErrorString());
        }
    }

    /**
     * kernel {@link DataType} → CEL 类型,与运行期数值规整({@link #normalizeNumber})保持一致:
     * <ul>
     *   <li>LONG→INT、DOUBLE→DOUBLE:运行期对应 Long/Double,int/double 误用在发布期即被 CEL 捕获;</li>
     *   <li>DECIMAL→DYN:运行期按实际值在 Long(无小数)/Double(带小数)间浮动,声明类型不可定,
     *       退化 dyn 避免误判合法脚本(如 {@code payload.amount > 10000});</li>
     *   <li>LIST/UNKNOWN→DYN:无精确 CEL 类型,放行不误报。</li>
     * </ul>
     */
    private static CelType celType(DataType dt) {
        return switch (dt) {
            case LONG -> SimpleType.INT;
            case DOUBLE -> SimpleType.DOUBLE;
            case STRING -> SimpleType.STRING;
            case BOOLEAN -> SimpleType.BOOL;
            case DATE, DATETIME -> SimpleType.TIMESTAMP;
            case DECIMAL, LIST, UNKNOWN -> SimpleType.DYN;
        };
    }

    /**
     * 绑定面规整,让真实数据源(JSON / DB)的值对齐 dev.cel 运行期期望的类型:
     * <ul>
     *   <li>{@code now}(Instant)→ protobuf {@link Timestamp}(CEL TIMESTAMP 运行期表示,不直接吃 Instant);</li>
     *   <li>{@code metrics}/{@code payload}/{@code subject} 命名空间内的数值规整为 CEL 数值类型——
     *       CEL int 仅有 {@code int64} 重载、double 仅有 {@code double} 重载且二者不互转。JSON 小整数是
     *       {@code Integer}、十进制是 {@code BigDecimal}/{@code Double},不规整会报 "no matching overload"。
     *       整型(Integer/Long/BigInteger 及无小数 BigDecimal)→ {@code Long};带小数(Float/Double/BigDecimal)→ {@code Double}。</li>
     * </ul>
     */
    private static Map<String, Object> adaptBindings(Map<String, Object> bindings) {
        Map<String, Object> adapted = new HashMap<>(bindings);
        Object now = bindings.get("now");
        if (now instanceof Instant instant) {
            adapted.put("now", Timestamp.newBuilder()
                    .setSeconds(instant.getEpochSecond())
                    .setNanos(instant.getNano())
                    .build());
        }
        for (String ns : new String[]{"metrics", "payload", "subject", "params"}) {
            if (bindings.get(ns) instanceof Map<?, ?> nsMap) {
                adapted.put(ns, normalizeNumerics(nsMap));
            }
        }
        return adapted;
    }

    /** 规整命名空间 map 内各值的数值类型(键透传),非数值原样保留。 */
    private static Map<String, Object> normalizeNumerics(Map<?, ?> nsMap) {
        Map<String, Object> out = HashMap.newHashMap(nsMap.size());
        for (Map.Entry<?, ?> e : nsMap.entrySet()) {
            out.put(String.valueOf(e.getKey()), normalizeNumber(e.getValue()));
        }
        return out;
    }

    /** 整型数值 → Long(CEL int64);带小数数值 → Double;非数值/ null 原样返回。 */
    private static Object normalizeNumber(Object v) {
        switch (v) {
            case null -> { return null; }
            case Long ignored -> { return v; }
            case Double ignored -> { return v; }
            case java.math.BigDecimal bd -> {
                // if/else 而非三元:三元混 long/double 会触发数值提升,把整型分支也变成 double
                if (bd.stripTrailingZeros().scale() <= 0) {
                    return bd.longValue();
                }
                return bd.doubleValue();
            }
            case Float f -> { return f.doubleValue(); }
            case java.math.BigInteger bi -> { return bi.longValue(); }
            case Integer i -> { return i.longValue(); }
            case Short s -> { return s.longValue(); }
            case Byte b -> { return b.longValue(); }
            default -> { return v; }
        }
    }
}
