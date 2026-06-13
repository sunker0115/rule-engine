package com.sstlfsj.rule.kernel.internal.context;

import com.google.common.hash.Hashing;
import com.sstlfsj.rule.kernel.api.annotation.MetricSourceType;
import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.api.spi.metric.MetricCache;
import com.sstlfsj.rule.kernel.api.spi.metric.MetricDefinitionResolver;
import com.sstlfsj.rule.kernel.api.spi.metric.MetricSourceHandler;
import com.sstlfsj.rule.kernel.api.spi.subject.SubjectLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * 装配 EvalContext：SubjectLoader（可选 SPI）+ provided 优先 + 按 sourceType 并发 fetch + 缓存 + 失败降级。
 * 纯 Java，无 Spring 依赖。fetch 相关依赖（resolver/cache/executor）为 null 时退化为"仅 providedMetrics 生效"。
 */
public class EvalContextAssembler {

    private static final Logger log = LoggerFactory.getLogger(EvalContextAssembler.class);

    private final SubjectLoader subjectLoader;
    private final Map<String, MetricSourceHandler> handlersBySourceType;
    private final MetricDefinitionResolver definitionResolver;
    private final MetricCache cache;
    private final ExecutorService fetchExecutor;
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
     * @param fetchExecutor        并发取数 ExecutorService（null 时用 ForkJoinPool.commonPool）
     * @param fetchTimeoutMs       全局取数超时毫秒（&le;0 表示不限）
     */
    public EvalContextAssembler(List<SubjectLoader> subjectLoaders,
                                Map<String, MetricSourceHandler> handlersBySourceType,
                                MetricDefinitionResolver definitionResolver,
                                MetricCache cache,
                                ExecutorService fetchExecutor,
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
     * @param event              触发事件
     * @param candidates         已过 Pre-Gate 的候选快照（取其 metricDependencies 并集为取数范围）
     * @param now                本次评估统一时刻
     * @param sceneDefaultParams 场景默认参数（注入 EvalContext，供规则引用）
     * @return 不可变 EvalContext
     */
    public EvalContext assemble(RuleEvent event,
                                List<RuleVersionSnapshot> candidates,
                                Instant now,
                                Map<String, Object> sceneDefaultParams) {
        Subject subject = loadSubject(event);

        // 无解析器：退化为历史行为——所有 providedMetrics 直接进 context（size 精确已知，预设免扩容）
        if (definitionResolver == null) {
            Map<String, MetricValue> provided = HashMap.newHashMap(event.providedMetrics().size());
            for (Map.Entry<String, Object> e : event.providedMetrics().entrySet()) {
                provided.put(e.getKey(), new MetricValue(e.getValue(), DataType.UNKNOWN.tag(), ValueSource.PROVIDED.tag()));
            }
            injectPayload(event, provided);
            return new EvalContext(event.tenantId(), event, subject, provided, now, sceneDefaultParams);
        }

        // 按绑定版本解析：同 code 多版本取最高版本（过渡期确定性策略）
        Map<String, Integer> chosenVersions = collectChosenVersions(candidates);
        // metrics 上界 = 选定版本指标数 + 候选未引用但仍推送的 provided 指标；descriptors 至多选定版本数。预设免 resize/rehash
        Map<String, MetricValue> metrics =
                HashMap.newHashMap(chosenVersions.size() + event.providedMetrics().size());
        Map<String, MetricDescriptor> descriptors = HashMap.newHashMap(chosenVersions.size());
        Set<String> needFetch = new LinkedHashSet<>();

        for (Map.Entry<String, Integer> entry : chosenVersions.entrySet()) {
            String code = entry.getKey();
            int version = entry.getValue();
            // 版本化缓存键：code:version 避免跨版本串味
            MetricDescriptor def = definitionResolver.resolve(event.tenantId(), code, version);
            if (def != null) descriptors.put(code, def);

            boolean hasProvided = event.providedMetrics().containsKey(code);
            if (hasProvided && (def == null || def.allowProvided())) {
                String dt = def != null ? def.dataType() : DataType.UNKNOWN.tag();
                metrics.put(code, new MetricValue(event.providedMetrics().get(code), dt, ValueSource.PROVIDED.tag()));
                continue;
            }
            if (hasProvided) {
                // allowProvided=false：忽略传值并 WARN（继续走 fetch）
                log.warn("metric={} allowProvided=false，忽略 providedMetrics 传值", code);
            }
            if (def == null) {
                metrics.put(code, MetricValue.error(EvalErrorCode.METRIC_FETCH_FAIL));
                continue;
            }
            if (cache != null && def.cacheTtlSeconds() > 0) {
                // 缓存键的 metricCode 段含 version，避免跨版本缓存污染
                MetricValue cached = cache.get(
                        cacheKey(event.tenantId(), code + ":" + version, event.subjectId(), def.params()));
                if (cached != null) { metrics.put(code, cached); continue; }
            }
            needFetch.add(code);
        }

        // 候选未引用但调用方仍推送的 provided 指标：补入（不影响 allowProvided 语义）
        for (Map.Entry<String, Object> e : event.providedMetrics().entrySet()) {
            if (!chosenVersions.containsKey(e.getKey())) {
                metrics.putIfAbsent(e.getKey(), new MetricValue(e.getValue(), DataType.UNKNOWN.tag(), ValueSource.PROVIDED.tag()));
            }
        }

        if (!needFetch.isEmpty()) {
            fetchConcurrently(event, now, needFetch, descriptors, metrics);
        }
        injectPayload(event, metrics);
        return new EvalContext(event.tenantId(), event, subject, metrics, now, sceneDefaultParams);
    }

    /**
     * 把事件 payload 的每个字段以 ValueSource.PAYLOAD 注入值 map（putIfAbsent，
     * 同名 metric/provided 优先）。payload 字段的 dataType 在比较时由 node.dataType()
     * （发布期冻结）决定，故此处统一 UNKNOWN。
     *
     * @param event  触发事件（payload 来源）
     * @param target 值 map（degraded 路径为 provided，正常路径为 metrics）
     */
    private static void injectPayload(RuleEvent event, Map<String, MetricValue> target) {
        Map<String, Object> payload = event.payload();
        if (payload == null) return;
        for (Map.Entry<String, Object> e : payload.entrySet()) {
            target.putIfAbsent(e.getKey(),
                    new MetricValue(e.getValue(), DataType.UNKNOWN.tag(), ValueSource.PAYLOAD.tag()));
        }
    }

    /**
     * 候选并集中每个 metricCode 选定一个解析版本：同 code 多版本时取最高（过渡期确定性策略）。
     */
    private static Map<String, Integer> collectChosenVersions(List<RuleVersionSnapshot> candidates) {
        Map<String, Integer> chosen = new LinkedHashMap<>();
        for (RuleVersionSnapshot snap : candidates) {
            for (MetricDependency dep : snap.metricDependencies()) {
                chosen.merge(dep.metricCode(), dep.metricVersion(), Math::max);
            }
        }
        return chosen;
    }

    private void fetchConcurrently(RuleEvent event, Instant now, Set<String> codes,
                                   Map<String, MetricDescriptor> descriptors,
                                   Map<String, MetricValue> metrics) {
        ExecutorService exec = fetchExecutor != null ? fetchExecutor : ForkJoinPool.commonPool();
        long timeoutMs = fetchTimeoutMs > 0 ? fetchTimeoutMs : Long.MAX_VALUE;

        List<String> orderedCodes = new ArrayList<>(codes);
        List<Callable<MetricValue>> tasks = new ArrayList<>(orderedCodes.size());
        for (String code : orderedCodes) {
            MetricDescriptor def = descriptors.get(code);
            MetricQuery query = new MetricQuery(code, event.tenantId(), event.subjectId(),
                    def.params(), event.payload(), now);
            MetricSourceHandler handler = handlersBySourceType.get(def.sourceType());
            tasks.add(() -> {
                if (handler == null) return MetricValue.error(EvalErrorCode.METRIC_FETCH_FAIL);
                try {
                    MetricValue v = handler.fetch(query);
                    return v != null ? v : MetricValue.error(EvalErrorCode.METRIC_FETCH_FAIL);
                } catch (Exception e) {
                    // 子任务内吞异常→降级（替代旧 .exceptionally）
                    return MetricValue.error(EvalErrorCode.METRIC_FETCH_FAIL);
                }
            });
        }

        List<Future<MetricValue>> results;
        try {
            results = exec.invokeAll(tasks, timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            // 调用线程被中断：恢复中断位并全部降级
            Thread.currentThread().interrupt();
            for (String code : orderedCodes) metrics.put(code, MetricValue.error(EvalErrorCode.METRIC_FETCH_FAIL));
            return;
        }

        for (int i = 0; i < orderedCodes.size(); i++) {
            String code = orderedCodes.get(i);
            Future<MetricValue> f = results.get(i);
            MetricValue v;
            if (f.isCancelled()) {
                // 超时未完成：invokeAll 已中断该子任务
                v = MetricValue.error(EvalErrorCode.METRIC_FETCH_FAIL);
            } else {
                try { v = f.get(); } catch (Exception e) { v = MetricValue.error(EvalErrorCode.METRIC_FETCH_FAIL); }
            }
            metrics.put(code, v);
            if (cache != null && !v.isError()) {
                MetricDescriptor def = descriptors.get(code);
                if (def.cacheTtlSeconds() > 0) {
                    // 缓存键的 metricCode 段含 version，与取时保持一致
                    cache.put(cacheKey(event.tenantId(), code + ":" + def.metricVersion(),
                            event.subjectId(), def.params()), v, def.cacheTtlSeconds());
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
