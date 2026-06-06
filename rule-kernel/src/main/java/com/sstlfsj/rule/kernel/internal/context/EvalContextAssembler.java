package com.sstlfsj.rule.kernel.internal.context;

import com.google.common.hash.Hashing;
import com.sstlfsj.rule.kernel.api.annotation.MetricSourceType;
import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.api.spi.metric.MetricCache;
import com.sstlfsj.rule.kernel.api.spi.metric.MetricDefinitionResolver;
import com.sstlfsj.rule.kernel.api.spi.metric.MetricSourceHandler;
import com.sstlfsj.rule.kernel.api.spi.subject.SubjectLoader;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * 装配 EvalContext：SubjectLoader（可选 SPI）+ provided 优先 + 按 sourceType 并发 fetch + 缓存 + 失败降级。
 * 纯 Java，无 Spring 依赖。fetch 相关依赖（resolver/cache/executor）为 null 时退化为"仅 providedMetrics 生效"。
 */
public class EvalContextAssembler {

    private static final String METRIC_FETCH_FAIL = "METRIC_FETCH_FAIL";

    private final SubjectLoader subjectLoader;
    private final Map<String, MetricSourceHandler> handlersBySourceType;
    private final MetricDefinitionResolver definitionResolver;
    private final MetricCache cache;
    private final Executor fetchExecutor;
    private final long fetchTimeoutMs;

    /**
     * 兼容构造：仅 providedMetrics 生效，无取数（保持历史行为，供本地 SDK / 测试使用）。
     *
     * @param subjectLoaders 可选 SubjectLoader 列表
     * @param metricHandlers 可选 MetricSourceHandler 列表（按 @MetricSourceType 归类）
     */
    public EvalContextAssembler(List<SubjectLoader> subjectLoaders,
                                List<MetricSourceHandler> metricHandlers) {
        this(subjectLoaders, toSourceTypeMap(metricHandlers), null, null, null, 0L);
    }

    /**
     * 取数构造。
     *
     * @param subjectLoaders       可选 SubjectLoader 列表
     * @param handlersBySourceType sourceType → handler 映射
     * @param definitionResolver   metric 定义解析器（null 时禁用 fetch）
     * @param cache                取数缓存（null 时不缓存）
     * @param fetchExecutor        并发取数线程池（null 时用 ForkJoinPool.commonPool）
     * @param fetchTimeoutMs       全局取数超时毫秒（&le;0 表示不限）
     */
    public EvalContextAssembler(List<SubjectLoader> subjectLoaders,
                                Map<String, MetricSourceHandler> handlersBySourceType,
                                MetricDefinitionResolver definitionResolver,
                                MetricCache cache,
                                Executor fetchExecutor,
                                long fetchTimeoutMs) {
        this.subjectLoader = pickUserLoader(subjectLoaders);
        this.handlersBySourceType =
                handlersBySourceType == null ? Map.of() : Map.copyOf(handlersBySourceType);
        this.definitionResolver = definitionResolver;
        this.cache = cache;
        this.fetchExecutor = fetchExecutor;
        this.fetchTimeoutMs = fetchTimeoutMs;
    }

    private static SubjectLoader pickUserLoader(List<SubjectLoader> loaders) {
        if (loaders == null) return null;
        return loaders.stream()
                .filter(l -> l.supportedTypes().contains(SubjectType.USER))
                .findFirst().orElse(null);
    }

    private static Map<String, MetricSourceHandler> toSourceTypeMap(List<MetricSourceHandler> handlers) {
        if (handlers == null) return Map.of();
        Map<String, MetricSourceHandler> m = new HashMap<>();
        for (MetricSourceHandler h : handlers) {
            MetricSourceType ann = h.getClass().getAnnotation(MetricSourceType.class);
            if (ann != null) m.put(ann.value(), h);
        }
        return m;
    }

    /**
     * 装配一次评估的 EvalContext。
     *
     * @param event      触发事件
     * @param candidates 已过 Pre-Gate 的候选快照（取其 metricDependencies 并集为取数范围）
     * @param now        本次评估统一时刻
     * @return 不可变 EvalContext
     */
    public EvalContext assemble(RuleEvent event,
                                List<RuleVersionSnapshot> candidates,
                                Instant now) {
        Subject subject = loadSubject(event);
        Map<String, MetricValue> metrics = new HashMap<>();

        // 无解析器：退化为历史行为——所有 providedMetrics 直接进 context
        if (definitionResolver == null) {
            for (Map.Entry<String, Object> e : event.providedMetrics().entrySet()) {
                metrics.put(e.getKey(), new MetricValue(e.getValue(), "UNKNOWN", "PROVIDED"));
            }
            return new EvalContext(event.tenantId(), event, subject, metrics, now);
        }

        Set<String> required = collectMetricCodes(candidates);
        Map<String, MetricDescriptor> descriptors = new HashMap<>();
        Set<String> needFetch = new LinkedHashSet<>();

        for (String code : required) {
            MetricDescriptor def = definitionResolver.resolve(event.tenantId(), code);
            if (def != null) descriptors.put(code, def);

            boolean hasProvided = event.providedMetrics().containsKey(code);
            if (hasProvided && (def == null || def.allowProvided())) {
                String dt = def != null ? def.dataType() : "UNKNOWN";
                metrics.put(code, new MetricValue(event.providedMetrics().get(code), dt, "PROVIDED"));
                continue;
            }
            if (hasProvided) {
                // allowProvided=false：忽略传值并 WARN（继续走 fetch）
                System.err.println("[EvalContextAssembler] metric=" + code
                        + " allowProvided=false，忽略 providedMetrics 传值");
            }
            if (def == null) {
                metrics.put(code, MetricValue.error(METRIC_FETCH_FAIL));
                continue;
            }
            if (cache != null && def.cacheTtlSeconds() > 0) {
                MetricValue cached = cache.get(
                        cacheKey(event.tenantId(), code, event.subjectId(), def.params()));
                if (cached != null) { metrics.put(code, cached); continue; }
            }
            needFetch.add(code);
        }

        // 候选未引用但调用方仍推送的 provided 指标：补入（不影响 allowProvided 语义）
        for (Map.Entry<String, Object> e : event.providedMetrics().entrySet()) {
            if (!required.contains(e.getKey())) {
                metrics.putIfAbsent(e.getKey(), new MetricValue(e.getValue(), "UNKNOWN", "PROVIDED"));
            }
        }

        if (!needFetch.isEmpty()) {
            fetchConcurrently(event, now, needFetch, descriptors, metrics);
        }
        return new EvalContext(event.tenantId(), event, subject, metrics, now);
    }

    private static Set<String> collectMetricCodes(List<RuleVersionSnapshot> candidates) {
        Set<String> codes = new LinkedHashSet<>();
        for (RuleVersionSnapshot snap : candidates) {
            codes.addAll(snap.metricDependencies());
        }
        return codes;
    }

    private void fetchConcurrently(RuleEvent event, Instant now, Set<String> codes,
                                   Map<String, MetricDescriptor> descriptors,
                                   Map<String, MetricValue> metrics) {
        Executor exec = fetchExecutor != null ? fetchExecutor : ForkJoinPool.commonPool();
        Map<String, CompletableFuture<MetricValue>> futures = new HashMap<>();
        for (String code : codes) {
            MetricDescriptor def = descriptors.get(code);
            MetricQuery query = new MetricQuery(code, event.tenantId(), event.subjectId(),
                    def.params(), event.payload(), now);
            MetricSourceHandler handler = handlersBySourceType.get(def.sourceType());
            futures.put(code, CompletableFuture
                    .supplyAsync(() -> {
                        if (handler == null) return MetricValue.error(METRIC_FETCH_FAIL);
                        MetricValue v = handler.fetch(query);
                        return v != null ? v : MetricValue.error(METRIC_FETCH_FAIL);
                    }, exec)
                    .exceptionally(ex -> MetricValue.error(METRIC_FETCH_FAIL)));
        }
        try {
            CompletableFuture.allOf(futures.values().toArray(new CompletableFuture[0]))
                    .get(fetchTimeoutMs > 0 ? fetchTimeoutMs : Long.MAX_VALUE, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            // 超时/中断：已完成 future 仍取其值，未完成的下方按 ERROR 处理
        }
        for (Map.Entry<String, CompletableFuture<MetricValue>> e : futures.entrySet()) {
            String code = e.getKey();
            CompletableFuture<MetricValue> f = e.getValue();
            MetricValue v;
            if (f.isDone() && !f.isCompletedExceptionally()) {
                v = f.getNow(MetricValue.error(METRIC_FETCH_FAIL));
            } else {
                v = MetricValue.error(METRIC_FETCH_FAIL);
                f.cancel(true);
            }
            metrics.put(code, v);
            if (cache != null && !v.isError()) {
                MetricDescriptor def = descriptors.get(code);
                if (def.cacheTtlSeconds() > 0) {
                    cache.put(cacheKey(event.tenantId(), code, event.subjectId(), def.params()),
                            v, def.cacheTtlSeconds());
                }
            }
        }
    }

    /** 缓存键 = tenant:metricCode:subjectId:murmur3(排序后 params)；params 顺序不影响键。 */
    private static String cacheKey(String tenantId, String metricCode, String subjectId,
                                   Map<String, Object> params) {
        String canonical = new TreeMap<>(params).toString();
        String h = Hashing.murmur3_128().hashString(canonical, StandardCharsets.UTF_8).toString();
        return tenantId + ":" + metricCode + ":" + subjectId + ":" + h;
    }

    private Subject loadSubject(RuleEvent event) {
        if (subjectLoader == null) {
            return new Subject(event.subjectId(), SubjectType.USER, Map.of());
        }
        try {
            return subjectLoader.load(event.subjectId(), SubjectType.USER, event);
        } catch (Exception e) {
            return new Subject(event.subjectId(), SubjectType.USER, Map.of());
        }
    }
}
