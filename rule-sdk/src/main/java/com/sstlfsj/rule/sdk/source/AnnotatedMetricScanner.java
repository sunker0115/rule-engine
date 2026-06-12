package com.sstlfsj.rule.sdk.source;

import com.sstlfsj.rule.kernel.api.model.DataType;
import com.sstlfsj.rule.kernel.api.model.MetricDescriptor;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.ValueSource;
import com.sstlfsj.rule.kernel.api.spi.metric.MetricSourceHandler;
import com.sstlfsj.rule.sdk.MetricQueryResolver;
import com.sstlfsj.rule.sdk.annotation.MetricSource;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 从所有 bean 收集 @MetricSource 方法,每个产出:合成 MetricSourceHandler(键=合成 sourceType
 * __anno_metric:&lt;code&gt;)+ 自动 MetricDescriptor(dataType 由返回类型推)。由 starter 灌进 client。
 */
public final class AnnotatedMetricScanner {

    private final MetricQueryResolver resolver;
    private final String tenantId;

    public AnnotatedMetricScanner(MetricQueryResolver resolver, String tenantId) {
        this.resolver = resolver;
        this.tenantId = tenantId == null ? "" : tenantId;
    }

    /** 合成 handler 表(sourceType→handler)+ 自动 descriptor 列表(都属 tenantId)。 */
    public record ScanResult(Map<String, MetricSourceHandler> handlers,
                             List<MetricDescriptor> descriptors, String tenantId) {}

    public ScanResult scan(List<?> beans) {
        Map<String, MetricSourceHandler> handlers = new HashMap<>();
        List<MetricDescriptor> descriptors = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (Object bean : beans) {
            for (Method m : bean.getClass().getMethods()) {
                MetricSource ann = m.getAnnotation(MetricSource.class);
                if (ann == null) continue;
                String code = ann.value();
                if (code.isBlank()) {
                    throw new IllegalStateException("@MetricSource.value 不可为空: " + m);
                }
                if (!seen.add(code)) {
                    throw new IllegalStateException("@MetricSource metricCode 重复: " + code);
                }
                resolver.validate(m.getParameters());
                String dataType = dataTypeTag(m.getReturnType());
                String sourceType = "__anno_metric:" + code;
                m.setAccessible(true);
                handlers.put(sourceType, wrap(bean, m, dataType));
                descriptors.add(new MetricDescriptor(
                        code, sourceType, dataType, ann.allowProvided(), ann.cacheTtlSeconds(), Map.of()));
            }
        }
        return new ScanResult(handlers, descriptors, tenantId);
    }

    private MetricSourceHandler wrap(Object bean, Method method, String dataType) {
        return query -> {
            try {
                Object[] args = resolver.resolve(method.getParameters(), query);
                Object ret = method.invoke(bean, args);
                return new MetricValue(ret, dataType, ValueSource.FETCHED.tag());
            } catch (Exception e) {
                return MetricValue.error("METRIC_SOURCE_EVAL_ERROR");
            }
        };
    }

    /** 返回类型 → DataType tag;不可映射类型抛错。 */
    private static String dataTypeTag(Class<?> t) {
        if (t == long.class || t == Long.class || t == int.class || t == Integer.class) return DataType.LONG.tag();
        if (t == double.class || t == Double.class || t == float.class || t == Float.class) return DataType.DOUBLE.tag();
        if (t == BigDecimal.class) return DataType.DECIMAL.tag();
        if (t == boolean.class || t == Boolean.class) return DataType.BOOLEAN.tag();
        if (t == String.class) return DataType.STRING.tag();
        throw new IllegalStateException("@MetricSource 返回类型无法映射到 DataType: " + t);
    }
}
