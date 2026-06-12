package com.sstlfsj.rule.expression.qlexpress;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.ql.util.express.DefaultContext;
import com.ql.util.express.ExpressRunner;
import com.ql.util.express.InstructionSet;
import com.ql.util.express.config.QLExpressRunStrategy;
import com.sstlfsj.rule.kernel.api.model.ExpressionLang;
import com.sstlfsj.rule.kernel.api.spi.expression.CompiledExpression;
import com.sstlfsj.rule.kernel.api.spi.expression.ExpressionCompileException;
import com.sstlfsj.rule.kernel.api.spi.expression.ExpressionEngine;
import com.sstlfsj.rule.kernel.api.spi.expression.ExpressionEvaluateException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * QLExpress 实现的运行期表达式引擎(EXPRESSION_SCRIPT 第三引擎)。
 * 弱类型/动态类型、线程安全单例;按源码内容缓存编译产物(Caffeine)。
 * 无文件/反射/类加载内建能力,safe-by-design。
 */
public final class QLExpressEngine implements ExpressionEngine {

    static {
        // safe-by-design 前提:开启 QLExpress 全局沙箱模式。沙箱下解析期不识别任意 Java 类
        // (java.lang.Runtime 之类降级为未定义变量链),求值期拒绝一切 Java 方法/静态属性调用,
        // 仅保留操作符与绑定数据(Map)访问。QLExpressRunStrategy 是进程级静态配置,设置一次即生效。
        QLExpressRunStrategy.setSandBoxMode(true);
    }

    /** metrics/payload/subject 命名空间下的点路径抽取:匹配 "ns.field"。 */
    private static final Pattern DOT_PATH = Pattern.compile("\\b(metrics|payload|subject)\\.([\\w][\\w\\d_-]*)");

    private final ExpressRunner runner;
    private final Cache<String, QLExpressCompiledExpression> cache;

    /** 默认缓存上限 10_000(脚本规则数量级远小于此)。 */
    public QLExpressEngine() {
        this(10_000);
    }

    /**
     * @param maxCachedExpressions 预编译缓存上限
     */
    public QLExpressEngine(long maxCachedExpressions) {
        this.runner = new ExpressRunner();
        this.cache = Caffeine.newBuilder().maximumSize(maxCachedExpressions).build();
    }

    @Override
    public String lang() {
        return ExpressionLang.QLEXPRESS.tag();
    }

    @Override
    public CompiledExpression compile(String source) {
        // 内容寻址缓存:同源脚本(跨规则/版本)共享一份编译产物
        return cache.get(source, this::doCompile);
    }

    private QLExpressCompiledExpression doCompile(String source) {
        try {
            InstructionSet is = runner.getInstructionSetFromLocalCache(source);
            return new QLExpressCompiledExpression(is, extractDotPaths(source));
        } catch (Exception e) {
            throw new ExpressionCompileException("QLExpress 编译失败: " + e.getMessage(), e);
        }
    }

    @Override
    public Object evaluate(CompiledExpression compiled, Map<String, Object> bindings) {
        QLExpressCompiledExpression qce = (QLExpressCompiledExpression) compiled;
        DefaultContext<String, Object> context = adaptBindings(bindings);
        List<String> errors = new ArrayList<>();
        try {
            // isCache=false:已通过 Caffeine 外层缓存,不走 runner 内层缓存
            // isTrace=false:生产环境不输出 trace 日志
            return runner.execute(qce.instructionSet(), context, errors, false, false);
        } catch (Exception e) {
            throw new ExpressionEvaluateException("QLExpress 求值失败: " + e.getMessage(), e);
        }
    }

    /**
     * 绑定面规整:Instant → epoch millis long(弱类型下数值比较最自然)。
     * QLExpress 弱类型自动提升数值(Integer/Double/BigDecimal 等),无需像 CEL 那样逐个规整。
     */
    private static DefaultContext<String, Object> adaptBindings(Map<String, Object> bindings) {
        DefaultContext<String, Object> ctx = new DefaultContext<>();
        for (Map.Entry<String, Object> e : bindings.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Instant instant) {
                v = instant.toEpochMilli();
            }
            ctx.put(e.getKey(), v);
        }
        return ctx;
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
