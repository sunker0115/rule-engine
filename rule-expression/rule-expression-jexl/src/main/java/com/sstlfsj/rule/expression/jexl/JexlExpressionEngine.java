package com.sstlfsj.rule.expression.jexl;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.sstlfsj.rule.kernel.api.model.ExpressionLang;
import com.sstlfsj.rule.kernel.api.spi.expression.CompiledExpression;
import com.sstlfsj.rule.kernel.api.spi.expression.ExpressionCompileException;
import com.sstlfsj.rule.kernel.api.spi.expression.ExpressionEngine;
import com.sstlfsj.rule.kernel.api.spi.expression.ExpressionEvaluateException;
import org.apache.commons.jexl3.JexlBuilder;
import org.apache.commons.jexl3.JexlEngine;
import org.apache.commons.jexl3.JexlException;
import org.apache.commons.jexl3.JexlScript;
import org.apache.commons.jexl3.MapContext;
import org.apache.commons.jexl3.introspection.JexlPermissions;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Apache Commons JEXL 实现的运行期表达式引擎(EXPRESSION_SCRIPT 第五引擎)。
 * 弱类型/动态类型、线程安全单例;按源码内容缓存编译产物(Caffeine)。
 *
 * <p>safe-by-design:引擎经 {@link JexlPermissions#RESTRICTED} 沙箱化,禁止脚本访问
 * {@code Runtime / System / ProcessBuilder / Thread / Class}、整个 {@code java.lang.reflect} 与
 * {@code java.net} 包、以及 {@code java.io.File} / {@code java.nio} 文件 API,
 * 切断 {@code getClass().forName(...)} 等反射逃逸链,只保留算术/比较与 String/Number/集合等安全操作。
 */
public final class JexlExpressionEngine implements ExpressionEngine {

    /** metrics/payload/subject 命名空间,发布期冻依赖只收这三类下的点路径。 */
    private static final Set<String> NAMESPACES = Set.of("metrics", "payload", "subject");

    private final JexlEngine jexl;
    private final Cache<String, JexlCompiledExpression> cache;

    /** 默认缓存上限 10_000(脚本规则数量级远小于此)。 */
    public JexlExpressionEngine() {
        this(10_000);
    }

    /**
     * @param maxCachedExpressions 预编译缓存上限
     */
    public JexlExpressionEngine(long maxCachedExpressions) {
        // 显式声明 RESTRICTED 沙箱权限,不依赖库默认值(安全前提必须落在代码里)
        this.jexl = new JexlBuilder().permissions(JexlPermissions.RESTRICTED).create();
        this.cache = Caffeine.newBuilder().maximumSize(maxCachedExpressions).build();
    }

    @Override
    public String lang() {
        return ExpressionLang.JEXL.tag();
    }

    @Override
    public CompiledExpression compile(String source) {
        // 内容寻址缓存:同源脚本(跨规则/版本)共享一份编译产物
        return cache.get(source, this::doCompile);
    }

    private JexlCompiledExpression doCompile(String source) {
        try {
            JexlScript script = jexl.createScript(source);
            return new JexlCompiledExpression(script, extractDotPaths(script));
        } catch (JexlException e) {
            throw new ExpressionCompileException("JEXL 编译失败: " + e.getMessage(), e);
        }
    }

    @Override
    public Object evaluate(CompiledExpression compiled, Map<String, Object> bindings) {
        JexlCompiledExpression jce = (JexlCompiledExpression) compiled;
        try {
            return jce.script().execute(new MapContext(adaptBindings(bindings)));
        } catch (JexlException e) {
            throw new ExpressionEvaluateException("JEXL 求值失败: " + e.getMessage(), e);
        }
    }

    /**
     * 绑定面规整:Instant → epoch millis long(JEXL 弱类型下数值比较最自然)。
     * JEXL 弱类型自动提升数值(Integer/Long/Double/BigDecimal 等),无需像 CEL 那样逐个规整。
     */
    private static Map<String, Object> adaptBindings(Map<String, Object> bindings) {
        Map<String, Object> adapted = new HashMap<>(bindings);
        Object now = bindings.get("now");
        if (now instanceof Instant instant) {
            adapted.put("now", instant.toEpochMilli());
        }
        return adapted;
    }

    /**
     * 从编译脚本抽取 metrics/payload/subject 命名空间下的点路径,供发布期冻依赖。
     * JEXL 的 {@link JexlScript#getVariables()} 直接给出 AST 解析出的变量引用(按层级拆成 List),
     * 比正则更准:如 {@code metrics.txn_cnt_1d} → {@code [metrics, txn_cnt_1d]}。
     */
    static Set<String> extractDotPaths(JexlScript script) {
        Set<String> vars = new LinkedHashSet<>();
        for (List<String> path : script.getVariables()) {
            if (path.size() >= 2 && NAMESPACES.contains(path.get(0))) {
                vars.add(String.join(".", path));
            }
        }
        return vars;
    }
}
