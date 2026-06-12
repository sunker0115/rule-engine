package com.sstlfsj.rule.expression.groovy;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.sstlfsj.rule.kernel.api.model.ExpressionLang;
import com.sstlfsj.rule.kernel.api.spi.expression.CompiledExpression;
import com.sstlfsj.rule.kernel.api.spi.expression.ExpressionCompileException;
import com.sstlfsj.rule.kernel.api.spi.expression.ExpressionEngine;
import com.sstlfsj.rule.kernel.api.spi.expression.ExpressionEvaluateException;
import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import groovy.lang.Script;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.runtime.InvokerHelper;
import org.kohsuke.groovy.sandbox.GroovyInterceptor;
import org.kohsuke.groovy.sandbox.SandboxTransformer;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Apache Groovy 实现的运行期表达式引擎(EXPRESSION_SCRIPT 第六引擎)。
 * 完整 JVM 脚本语言、线程安全单例;按源码内容缓存编译产物(Caffeine)。
 *
 * <p>safe-by-design:Groovy 本体无运行期沙箱,故采用 groovy-sandbox 的
 * {@link SandboxTransformer}(编译期把每次调用改写为经拦截器转发) + {@link GroovySandboxInterceptor}
 * (deny-by-default 白名单,运行期逐调用过滤)。每次 evaluate 在当前线程 register/unregister 拦截器,
 * 切断命令执行/反射/类加载/构造任意对象等逃逸。详见 {@link GroovySandboxInterceptor}。
 */
public final class GroovyExpressionEngine implements ExpressionEngine {

    /** metrics/payload/subject 命名空间下的点路径抽取:匹配 "ns.field"。 */
    private static final Pattern DOT_PATH = Pattern.compile("\\b(metrics|payload|subject)\\.([\\w][\\w\\d_-]*)");

    private final GroovyShell shell;
    private final GroovyInterceptor sandbox;
    private final Cache<String, GroovyCompiledExpression> cache;

    /** 默认缓存上限 10_000(脚本规则数量级远小于此)。 */
    public GroovyExpressionEngine() {
        this(10_000);
    }

    /**
     * @param maxCachedExpressions 预编译缓存上限
     */
    public GroovyExpressionEngine(long maxCachedExpressions) {
        CompilerConfiguration cc = new CompilerConfiguration();
        // SandboxTransformer 把脚本里每次方法/构造/静态调用/属性访问改写为经拦截器转发;
        // 安全完全由运行期 GroovySandboxInterceptor 兜底(不叠加 SecureASTCustomizer——
        // 其 indirectImportCheck 实测会误伤合法方法调用,且无法挡运行期动态分发)。
        cc.addCompilationCustomizers(new SandboxTransformer());
        this.shell = new GroovyShell(cc);
        this.sandbox = new GroovySandboxInterceptor();
        this.cache = Caffeine.newBuilder().maximumSize(maxCachedExpressions).build();
    }

    @Override
    public String lang() {
        return ExpressionLang.GROOVY.tag();
    }

    @Override
    public CompiledExpression compile(String source) {
        // 内容寻址缓存:同源脚本(跨规则/版本)共享一份编译产物
        return cache.get(source, this::doCompile);
    }

    private GroovyCompiledExpression doCompile(String source) {
        try {
            Class<?> scriptClass = shell.getClassLoader().parseClass(source);
            return new GroovyCompiledExpression(scriptClass, extractDotPaths(source));
        } catch (CompilationFailedException e) {
            throw new ExpressionCompileException("Groovy 编译失败: " + e.getMessage(), e);
        }
    }

    @Override
    public Object evaluate(CompiledExpression compiled, Map<String, Object> bindings) {
        GroovyCompiledExpression gce = (GroovyCompiledExpression) compiled;
        // 每次实例化新 Script + 新 Binding,天然线程安全;拦截器按当前线程注册
        Script script = InvokerHelper.createScript(gce.scriptClass(), new Binding(adaptBindings(bindings)));
        sandbox.register();
        try {
            return script.run();
        } catch (Exception e) {
            throw new ExpressionEvaluateException("Groovy 求值失败: " + e.getMessage(), e);
        } finally {
            sandbox.unregister();
        }
    }

    /**
     * 绑定面规整:Instant → epoch millis long(Groovy 弱类型下数值比较最自然)。
     * Groovy 弱类型自动提升数值(Integer/Long/Double/BigDecimal 等),无需像 CEL 那样逐个规整。
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
