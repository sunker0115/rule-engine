package com.sstlfsj.rule.eval;

import com.sstlfsj.rule.eval.internal.action.ActionDispatchService;
import com.sstlfsj.rule.eval.internal.repository.ActionExecutionMapper;
import com.sstlfsj.rule.eval.internal.repository.SceneActionBindingReadMapper;
import com.sstlfsj.rule.kernel.api.annotation.ActionType;
import com.sstlfsj.rule.kernel.api.spi.action.ActionHandler;
import com.sstlfsj.rule.kernel.api.spi.executor.RuleVersionExecutor;
import com.sstlfsj.rule.kernel.api.spi.metric.MetricSourceHandler;
import com.sstlfsj.rule.kernel.api.spi.pregate.PreGate;
import com.sstlfsj.rule.kernel.api.spi.subject.SubjectLoader;
import com.sstlfsj.rule.kernel.internal.codec.SnapshotAssembler;
import com.sstlfsj.rule.kernel.internal.condition.KernelEvaluators;
import com.sstlfsj.rule.kernel.internal.context.EvalContextAssembler;
import com.sstlfsj.rule.kernel.internal.engine.EvalEngine;
import com.sstlfsj.rule.kernel.internal.evaluator.ScorecardExecutor;
import com.sstlfsj.rule.kernel.internal.evaluator.TracingInterpretedExecutor;
import com.sstlfsj.rule.kernel.internal.index.SceneRuleIndex;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Primary;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 自动装配规则评估模块。 */
@AutoConfiguration
@ComponentScan("com.sstlfsj.rule.eval.internal")
public class EvalAutoConfiguration {

    /**
     * 默认使用 TracingInterpretedExecutor（AST 树形解释执行，附带 NodeTrace 收集）。
     * 外部可注册自定义 RuleVersionExecutor Bean 覆盖此默认值。
     *
     * @return TracingInterpretedExecutor 实例
     */
    @Bean
    @Primary
    public RuleVersionExecutor ruleVersionExecutor() {
        return new TracingInterpretedExecutor(KernelEvaluators.defaults());
    }

    /**
     * 注册 ScorecardExecutor，供 kind=SCORECARD 的规则版本评估使用。
     *
     * @return ScorecardExecutor 实例
     */
    @Bean
    public ScorecardExecutor scorecardExecutor() {
        return new ScorecardExecutor(KernelEvaluators.defaults());
    }

    /**
     * 纯 Java SnapshotAssembler，将 RuleVersionRow 组装为 RuleVersionSnapshot。
     *
     * @return SnapshotAssembler 实例
     */
    @Bean
    public SnapshotAssembler snapshotAssembler() {
        return new SnapshotAssembler();
    }

    /**
     * 内存倒排索引，供 IndexStartupLoader / EventListener 更新，EvalServiceImpl 查询。
     *
     * @return SceneRuleIndex 实例
     */
    @Bean
    public SceneRuleIndex sceneRuleIndex() {
        return new SceneRuleIndex();
    }

    /**
     * 装配 EvalContext；SubjectLoader / MetricSourceHandler 由 SPI 可选注入。
     *
     * @param subjectLoaders  可选 SubjectLoader SPI 实现列表
     * @param metricHandlers  可选 MetricSourceHandler SPI 实现列表
     * @return EvalContextAssembler 实例
     */
    @Bean
    public EvalContextAssembler evalContextAssembler(
            @Autowired(required = false) List<SubjectLoader> subjectLoaders,
            @Autowired(required = false) List<MetricSourceHandler> metricHandlers) {
        return new EvalContextAssembler(
                subjectLoaders == null ? List.of() : subjectLoaders,
                metricHandlers == null ? List.of() : metricHandlers);
    }

    /**
     * 纯 Java 评估编排器：Matcher → Pre-Gate → EvalContext → Executor。
     * 无 DB 写入、无 Action 派发；key 映射 kind 字段到对应 executor。
     *
     * @param sceneRuleIndex       倒排索引
     * @param evalContextAssembler 上下文装配
     * @param preGates             可选 PreGate SPI 实现列表
     * @param ruleVersionExecutor  AST_BOOLEAN executor
     * @param scorecardExecutor    SCORECARD executor
     * @return EvalEngine 实例
     */
    @Bean
    public EvalEngine evalEngine(
            SceneRuleIndex sceneRuleIndex,
            EvalContextAssembler evalContextAssembler,
            @Autowired(required = false) List<PreGate> preGates,
            RuleVersionExecutor ruleVersionExecutor,
            ScorecardExecutor scorecardExecutor) {
        Map<String, PreGate> gateMap = new HashMap<>();
        if (preGates != null) {
            preGates.forEach(g -> gateMap.put(g.gateType(), g));
        }
        return new EvalEngine(sceneRuleIndex, evalContextAssembler, gateMap,
                Map.of("AST_BOOLEAN", ruleVersionExecutor, "SCORECARD", scorecardExecutor));
    }

    /**
     * 注册 ActionDispatchService，按 @ActionType.value() 构建 handler 映射。
     *
     * @param actionHandlers  Spring 容器中所有 ActionHandler bean（可为空）
     * @param bindingMapper   scene_action_binding 只读 Mapper
     * @param executionMapper action_execution 写 Mapper
     * @return ActionDispatchService 实例
     */
    @Bean
    public ActionDispatchService actionDispatchService(
            @Autowired(required = false) List<ActionHandler> actionHandlers,
            SceneActionBindingReadMapper bindingMapper,
            ActionExecutionMapper executionMapper) {
        Map<String, ActionHandler> handlerMap = new HashMap<>();
        if (actionHandlers != null) {
            for (ActionHandler handler : actionHandlers) {
                ActionType ann = handler.getClass().getAnnotation(ActionType.class);
                if (ann != null) {
                    handlerMap.put(ann.value(), handler);
                }
            }
        }
        return new ActionDispatchService(handlerMap, bindingMapper, executionMapper);
    }
}
