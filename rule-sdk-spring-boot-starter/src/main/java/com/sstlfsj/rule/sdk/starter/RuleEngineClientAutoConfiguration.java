package com.sstlfsj.rule.sdk.starter;

import com.sstlfsj.rule.kernel.api.annotation.ConditionType;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;
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
            Optional<EvalResultListener> evalResultListener,
            Optional<EvalSessionListener> evalSessionListener,
            ObjectProvider<MetricSourceHandler> metricHandlers,
            ObjectProvider<MetricDefinitionResolver> metricDefinitionResolver,
            ObjectProvider<MetricCache> metricCache,
            ObjectProvider<MetricDefinitionSource> metricDefinitionSources) {

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

        // Listener Bean 注入
        evalResultListener.ifPresent(builder::evalResultListener);
        evalSessionListener.ifPresent(builder::evalSessionListener);

        // 取数 SPI 自动注入：handler 由宿主提供，注入任一即启用 fetch
        metricHandlers.forEach(builder::metricSourceHandler);
        metricDefinitionResolver.ifAvailable(builder::metricDefinitionResolver);
        metricCache.ifAvailable(builder::metricCache);
        metricDefinitionSources.forEach(builder::metricDefinitionSource);

        return builder.build();
    }
}
