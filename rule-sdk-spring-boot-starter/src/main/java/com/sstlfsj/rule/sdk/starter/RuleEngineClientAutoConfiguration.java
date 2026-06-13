package com.sstlfsj.rule.sdk.starter;

import com.sstlfsj.rule.kernel.api.annotation.ConditionType;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;
import com.sstlfsj.rule.kernel.api.spi.expression.ExpressionEngine;
import com.sstlfsj.rule.kernel.api.spi.metric.MetricCache;
import com.sstlfsj.rule.kernel.api.spi.metric.MetricDefinitionResolver;
import com.sstlfsj.rule.kernel.api.spi.metric.MetricSourceHandler;
import com.sstlfsj.rule.sdk.EvalResultListener;
import com.sstlfsj.rule.sdk.EvalSessionListener;
import com.sstlfsj.rule.sdk.InlineRuleSpec;
import com.sstlfsj.rule.sdk.RuleEngineClient;
import com.sstlfsj.rule.sdk.source.AnnotationRuleSource;
import com.sstlfsj.rule.sdk.source.MetricDefinitionSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 自动装配 RuleEngineClient，读取 rule.sdk.* 配置。
 * 支持 HTTP 轮询、JSON 文件、混用三种模式，自动扫描 @ConditionType Bean 与 Listener Bean。
 */
@AutoConfiguration
@EnableConfigurationProperties(SdkProperties.class)
public class RuleEngineClientAutoConfiguration {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(RuleEngineClientAutoConfiguration.class);

    /**
     * 注册 RuleEngineClient Bean。
     * 业务方注册了自定义 RuleEngineClient Bean 时此 Bean 不生效。
     *
     * @param props                     rule.sdk.* 配置
     * @param ctx                       Spring 容器，用于扫描 @ConditionType / Listener Bean
     * @param evalResultListener        可选，存在时自动注入
     * @param evalSessionListener       可选，存在时自动注入
     * @param metricHandlers            宿主自带取数 handler（可选，按 @MetricSourceType 归类）
     * @param metricDefinitionResolver  自定义定义解析器（可选）
     * @param metricCache               取数缓存（可选）
     * @param metricDefinitionSources   metric 定义来源（可选）
     * @return RuleEngineClient 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public RuleEngineClient ruleEngineClient(
            SdkProperties props,
            ApplicationContext ctx,
            ApplicationEventPublisher eventPublisher,
            Optional<EvalResultListener> evalResultListener,
            Optional<EvalSessionListener> evalSessionListener,
            ObjectProvider<MetricSourceHandler> metricHandlers,
            ObjectProvider<MetricDefinitionResolver> metricDefinitionResolver,
            ObjectProvider<MetricCache> metricCache,
            ObjectProvider<MetricDefinitionSource> metricDefinitionSources,
            ObjectProvider<ExpressionEngine> expressionEngines) {

        RuleEngineClient.Builder builder = RuleEngineClient.builder();

        // HTTP 轮询模式
        if (props.getServerUrl() != null && !props.getServerUrl().isBlank()) {
            builder.serverUrl(props.getServerUrl())
                   .tenantId(props.getTenantId())
                   .fetchMode(props.getFetchMode())
                   .pollInterval(props.getPollInterval());
            if (props.getScenes() != null) {
                props.getScenes().forEach(builder::scenes);
            }
        }

        // 文件模式
        if (props.getRuleFiles() != null) {
            props.getRuleFiles().forEach(builder::ruleFile);
        }

        // @ConditionType Bean 自动扫描：实现了 ConditionEvaluator 的 Bean 注册为算子
        ctx.getBeansWithAnnotation(ConditionType.class).forEach((name, bean) -> {
            if (bean instanceof ConditionEvaluator evaluator) {
                ConditionType ann = bean.getClass().getAnnotation(ConditionType.class);
                builder.addEvaluator(ann.value(), evaluator);
            }
        });

        // @RuleDef / InlineRuleSpec Bean 自动装载
        List<InlineRuleSpec> inlineSpecs = new ArrayList<>(
                ctx.getBeansOfType(InlineRuleSpec.class).values());
        if (!inlineSpecs.isEmpty()) {
            builder.ruleSource(new AnnotationRuleSource(inlineSpecs, props.getTenantId()));
        }

        // 注解规则(@RuleDef + @Condition 方法)装配:扫描 → 合成算子 + 快照
        com.sstlfsj.rule.sdk.FactResolver factResolver = new com.sstlfsj.rule.sdk.FactResolver();
        List<Object> annotatedRuleBeans = new ArrayList<>();
        ctx.getBeansWithAnnotation(com.sstlfsj.rule.kernel.api.annotation.RuleDef.class)
           .forEach((name, bean) -> {
               for (java.lang.reflect.Method m : bean.getClass().getMethods()) {
                   if (m.isAnnotationPresent(com.sstlfsj.rule.sdk.annotation.Condition.class)
                           || m.isAnnotationPresent(com.sstlfsj.rule.sdk.annotation.Decide.class)
                           || m.isAnnotationPresent(com.sstlfsj.rule.sdk.annotation.Score.class)) {
                       annotatedRuleBeans.add(bean);
                       break;
                   }
               }
           });
        if (!annotatedRuleBeans.isEmpty()) {
            com.sstlfsj.rule.sdk.source.AnnotatedRuleScanner.ScanResult scan =
                    new com.sstlfsj.rule.sdk.source.AnnotatedRuleScanner(factResolver, props.getTenantId())
                            .scan(annotatedRuleBeans);
            scan.evaluators().forEach(builder::addEvaluator);
            builder.addDecideInvocations(scan.decideInvocations());
            builder.addScoreInvocations(scan.scoreInvocations());
            builder.ruleSource(new com.sstlfsj.rule.sdk.source.DslRuleSource(scan.snapshots()));
        }

        // @MetricSource 方法式取数:扫描所有 bean → 合成 handler + 自动 descriptor
        java.util.List<Object> metricSourceBeans = beansWith(ctx,
                com.sstlfsj.rule.sdk.annotation.MetricSource.class);
        if (!metricSourceBeans.isEmpty()) {
            com.sstlfsj.rule.sdk.source.AnnotatedMetricScanner.ScanResult scan =
                    new com.sstlfsj.rule.sdk.source.AnnotatedMetricScanner(
                            new com.sstlfsj.rule.sdk.MetricQueryResolver(), props.getTenantId())
                            .scan(metricSourceBeans);
            scan.handlers().forEach(builder::addMetricSourceHandler);
            scan.descriptors().forEach(d -> builder.localMetric(scan.tenantId(), d));
        }

        // 动作派发:Spring 事件 sink(甲) + @OnDecision sink(乙),装进 DecisionDispatcher
        java.util.concurrent.Executor onDecisionExecutor = new java.util.concurrent.ThreadPoolExecutor(
                1, 4, 60L, java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(1024),
                r -> { Thread t = new Thread(r, "ondecision-async"); t.setDaemon(true); return t; },
                new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        OnDecisionInvoker onDecisionInvoker = new OnDecisionInvoker(
                factResolver,
                new ArrayList<>(beansWith(ctx, com.sstlfsj.rule.sdk.annotation.OnDecision.class)),
                onDecisionExecutor);
        com.sstlfsj.rule.sdk.DecisionSink springSink = eventPublisher::publishEvent;
        builder.decisionContextListener(new com.sstlfsj.rule.sdk.DecisionDispatcher(
                List.of(springSink, onDecisionInvoker)));

        // orphan @OnDecision 启动核对:订阅了"没有任何本地注解规则产出"的决策码 → warn(疑似拼写)
        java.util.Set<String> producedCodes = new java.util.HashSet<>();
        for (Object bean : annotatedRuleBeans) {
            com.sstlfsj.rule.kernel.api.annotation.RuleDef rd =
                    bean.getClass().getAnnotation(com.sstlfsj.rule.kernel.api.annotation.RuleDef.class);
            if (rd != null) {
                for (com.sstlfsj.rule.kernel.api.annotation.DecisionBinding d : rd.decisions()) {
                    producedCodes.add(d.code());
                }
            }
        }
        for (String code : onDecisionInvoker.subscribedCodes()) {
            if (!producedCodes.contains(code)) {
                log.warn("@OnDecision 订阅的决策码 '{}' 没有任何本地注解规则产出,疑似拼写错误或依赖服务端规则", code);
            }
        }

        // Listener Bean 注入
        evalResultListener.ifPresent(builder::evalResultListener);
        evalSessionListener.ifPresent(builder::evalSessionListener);

        // 取数 SPI 自动注入：handler 由宿主提供，注入任一即启用 fetch
        metricHandlers.forEach(builder::metricSourceHandler);
        metricDefinitionResolver.ifAvailable(builder::metricDefinitionResolver);
        metricCache.ifAvailable(builder::metricCache);
        metricDefinitionSources.forEach(builder::metricDefinitionSource);

        // 表达式引擎自动收集:classpath 上有 CEL starter(注册 CelExpressionEngine bean)即被纳入,
        // 启用 EXPRESSION_SCRIPT 脚本规则执行;无则脚本规则优雅 SCRIPT_NO_ENGINE。本 starter 不依赖任何具体引擎。
        expressionEngines.forEach(builder::expressionEngine);

        return builder.build();
    }

    /**
     * 扫描容器中所有 bean,挑出"至少有一个方法标了指定方法注解"的 bean。
     * 供 @OnDecision / @MetricSource 等方法式注解的 bean 收集复用。
     *
     * @param ctx             Spring 容器
     * @param methodAnnotation 目标方法注解类型
     * @return 命中的 bean 列表(去重由容器单例保证)
     */
    private static List<Object> beansWith(ApplicationContext ctx,
            Class<? extends java.lang.annotation.Annotation> methodAnnotation) {
        List<Object> result = new ArrayList<>();
        for (Object bean : ctx.getBeansOfType(Object.class).values()) {
            for (java.lang.reflect.Method m : bean.getClass().getMethods()) {
                if (m.isAnnotationPresent(methodAnnotation)) {
                    result.add(bean);
                    break;
                }
            }
        }
        return result;
    }
}
