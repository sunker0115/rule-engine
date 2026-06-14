package com.sstlfsj.rule.expression.jsonlogic;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.sstlfsj.rule.kernel.api.model.ExpressionLang;
import com.sstlfsj.rule.kernel.api.spi.expression.CompiledExpression;
import com.sstlfsj.rule.kernel.api.spi.expression.ExpressionCompileException;
import com.sstlfsj.rule.kernel.api.spi.expression.ExpressionEngine;
import com.sstlfsj.rule.kernel.api.spi.expression.ExpressionEvaluateException;
import io.github.jamsesso.jsonlogic.JsonLogic;
import io.github.jamsesso.jsonlogic.JsonLogicException;

import java.lang.reflect.Type;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JsonLogic 实现的运行期表达式引擎(EXPRESSION_SCRIPT 第四引擎)。
 * 纯 JSON 规则引擎,无代码执行能力(天然 safe-by-design);线程安全单例;按源码内容缓存编译产物(Caffeine)。
 *
 * <p>表达式源码为 JSON 格式的 JsonLogic 规则,如:
 * <pre>{@code {"if": [{">": [{"var": "payload.amount"}, 10000]}, "REVIEW", "PASS"]}}</pre>
 *
 * <p>变量通过 {@code {"var": "metrics.xxx"}} / {@code {"var": "payload.yyy"}} 引用,
 * 解析为 Map 后逐层按点路径索引(如 {@code payload.amount → data["payload"]["amount"]})。
 */
public final class JsonLogicExpressionEngine implements ExpressionEngine {

    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {}.getType();

    private final JsonLogic jsonLogic;
    private final Gson gson;
    private final Cache<String, JsonLogicCompiledExpression> cache;

    /** 默认缓存上限 10_000(脚本规则数量级远小于此)。 */
    public JsonLogicExpressionEngine() {
        this(10_000);
    }

    /**
     * @param maxCachedExpressions 预编译缓存上限
     */
    public JsonLogicExpressionEngine(long maxCachedExpressions) {
        this.jsonLogic = new JsonLogic();
        this.gson = new Gson();
        this.cache = Caffeine.newBuilder().maximumSize(maxCachedExpressions).build();
    }

    @Override
    public String lang() {
        return ExpressionLang.JSONLOGIC.tag();
    }

    @Override
    public CompiledExpression compile(String source) {
        // 内容寻址缓存:同源脚本(跨规则/版本)共享一份编译产物
        return cache.get(source, this::doCompile);
    }

    private JsonLogicCompiledExpression doCompile(String source) {
        try {
            Map<String, Object> rule = gson.fromJson(source, MAP_TYPE);
            return new JsonLogicCompiledExpression(source, extractVarPaths(rule));
        } catch (com.google.gson.JsonSyntaxException e) {
            throw new ExpressionCompileException("JsonLogic 编译失败(JSON 解析错误): " + e.getMessage(), e);
        } catch (Exception e) {
            throw new ExpressionCompileException("JsonLogic 编译失败: " + e.getMessage(), e);
        }
    }

    @Override
    public Object evaluate(CompiledExpression compiled, Map<String, Object> bindings) {
        JsonLogicCompiledExpression jce = (JsonLogicCompiledExpression) compiled;
        try {
            // json-logic-java 的 apply(String, Object) 接收 JSON 字符串规则
            return jsonLogic.apply(jce.source(), adaptBindings(bindings));
        } catch (JsonLogicException e) {
            throw new ExpressionEvaluateException("JsonLogic 求值失败: " + e.getMessage(), e);
        }
    }

    /**
     * 绑定面规整:Instant → epoch millis long(JsonLogic 无日期类型,数值比较最自然)。
     * JsonLogic 的 var 操作用点路径在 data Map 中索引,故 bindings 保持 Map 结构传递即可。
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
     * 从 JSON 规则中递归抽取所有 {@code {"var": "..."}} 的路径值。
     * 只保留 metrics/payload/subject 命名空间下的点路径。
     */
    static Set<String> extractVarPaths(Map<String, Object> rule) {
        Set<String> paths = new LinkedHashSet<>();
        walkForVars(rule, paths);
        return paths;
    }

    private static void walkForVars(Object node, Set<String> paths) {
        if (node instanceof Map<?, ?> map) {
            if (map.containsKey("var") && map.get("var") instanceof String varPath && !varPath.isEmpty()) {
                // 只收 metrics. / payload. / subject. 开头的点路径
                if (varPath.startsWith("metrics.") || varPath.startsWith("payload.") || varPath.startsWith("subject.")) {
                    paths.add(varPath);
                }
            }
            for (Object value : map.values()) {
                walkForVars(value, paths);
            }
        } else if (node instanceof List<?> list) {
            for (Object item : list) {
                walkForVars(item, paths);
            }
        }
    }
}
