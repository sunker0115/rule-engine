package com.sstlfsj.rule.expression.aviator;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.googlecode.aviator.AviatorEvaluator;
import com.googlecode.aviator.Expression;
import com.sstlfsj.rule.kernel.api.model.ExpressionLang;
import com.sstlfsj.rule.kernel.api.spi.expression.CompiledExpression;
import com.sstlfsj.rule.kernel.api.spi.expression.ExpressionCompileException;
import com.sstlfsj.rule.kernel.api.spi.expression.ExpressionEngine;
import com.sstlfsj.rule.kernel.api.spi.expression.ExpressionEvaluateException;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Aviator 实现的运行期表达式引擎(EXPRESSION_SCRIPT 第二引擎)。
 * 弱类型/动态类型、线程安全单例;按源码内容缓存编译产物(Caffeine)。
 * 无文件/反射/类加载内建能力,safe-by-design。
 */
public final class AviatorExpressionEngine implements ExpressionEngine {

    /** metrics/payload/subject 命名空间下的点路径抽取:匹配 "ns.field"。 */
    private static final Pattern DOT_PATH = Pattern.compile("\\b(metrics|payload|subject)\\.([\\w][\\w\\d_-]*)");

    private final Cache<String, AviatorCompiledExpression> cache;

    /** 默认缓存上限 10_000(脚本规则数量级远小于此)。 */
    public AviatorExpressionEngine() {
        this(10_000);
    }

    /**
     * @param maxCachedExpressions 预编译缓存上限
     */
    public AviatorExpressionEngine(long maxCachedExpressions) {
        this.cache = Caffeine.newBuilder().maximumSize(maxCachedExpressions).build();
    }

    @Override
    public String lang() {
        return ExpressionLang.AVIATOR.tag();
    }

    @Override
    public CompiledExpression compile(String source) {
        // 内容寻址缓存:同源脚本(跨规则/版本)共享一份编译产物
        return cache.get(source, this::doCompile);
    }

    private AviatorCompiledExpression doCompile(String source) {
        try {
            Expression exp = AviatorEvaluator.compile(source, true);
            return new AviatorCompiledExpression(exp, extractDotPaths(source));
        } catch (Exception e) {
            throw new ExpressionCompileException("Aviator 编译失败: " + e.getMessage(), e);
        }
    }

    @Override
    public Object evaluate(CompiledExpression compiled, Map<String, Object> bindings) {
        AviatorCompiledExpression ace = (AviatorCompiledExpression) compiled;
        try {
            return ace.expression().execute(adaptBindings(bindings));
        } catch (Exception e) {
            throw new ExpressionEvaluateException("Aviator 求值失败: " + e.getMessage(), e);
        }
    }

    /**
     * 绑定面规整:Instant → epoch millis long(Aviator 弱类型下数值比较最自然)。
     * Aviator 弱类型自动提升数值(Integer/Double/BigDecimal 等),无需像 CEL 那样逐个规整。
     */
    private static Map<String, Object> adaptBindings(Map<String, Object> bindings) {
        Map<String, Object> adapted = new HashMap<>(bindings);
        Object now = bindings.get("now");
        if (now instanceof Instant instant) {
            adapted.put("now", instant.toEpochMilli());
        }
        return adapted;
    }

    /** 从源码提取 metrics/payload/subject 命名空间下的点路径,供发布期冻依赖。 */
    static Set<String> extractDotPaths(String source) {
        Set<String> vars = new LinkedHashSet<>();
        Matcher m = DOT_PATH.matcher(source);
        while (m.find()) {
            vars.add(m.group(1) + "." + m.group(2));
        }
        return vars;
    }
}
