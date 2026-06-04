package com.sstlfsj.rule.eval;

import com.sstlfsj.rule.eval.internal.action.ActionDispatchService;
import com.sstlfsj.rule.eval.internal.repository.ActionExecutionMapper;
import com.sstlfsj.rule.eval.internal.repository.SceneActionBindingReadMapper;
import com.sstlfsj.rule.kernel.api.annotation.ActionType;
import com.sstlfsj.rule.kernel.api.spi.action.ActionHandler;
import com.sstlfsj.rule.kernel.api.spi.executor.RuleVersionExecutor;
import com.sstlfsj.rule.kernel.internal.evaluator.ScorecardExecutor;
import com.sstlfsj.rule.kernel.internal.evaluator.TracingInterpretedExecutor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

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
     * @param conditionEvaluators 所有注册的 ConditionEvaluator，按 conditionType 索引
     * @return TracingInterpretedExecutor 实例
     */
    @Bean
    @org.springframework.context.annotation.Primary
    public RuleVersionExecutor ruleVersionExecutor(
            @Autowired(required = false)
            Map<String, com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator> conditionEvaluators) {
        return new TracingInterpretedExecutor(
                conditionEvaluators == null ? Map.of() : conditionEvaluators);
    }

    /**
     * 注册 ScorecardExecutor，供 kind=SCORECARD 的规则版本评估使用。
     *
     * @param conditionEvaluators 所有注册的 ConditionEvaluator，按 conditionType 索引
     * @return ScorecardExecutor 实例
     */
    @Bean
    public ScorecardExecutor scorecardExecutor(
            @Autowired(required = false)
            Map<String, com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator> conditionEvaluators) {
        return new ScorecardExecutor(conditionEvaluators == null ? Map.of() : conditionEvaluators);
    }

    /**
     * 注册 ActionDispatchService，按 @ActionType.value() 构建 handler 映射。
     * List<ActionHandler> 由 Spring 自动收集容器中所有 ActionHandler bean。
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
