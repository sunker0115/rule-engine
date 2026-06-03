package com.sstlfsj.rule.eval;

import com.sstlfsj.rule.kernel.api.spi.executor.RuleVersionExecutor;
import com.sstlfsj.rule.kernel.internal.evaluator.TracingInterpretedExecutor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

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
    public RuleVersionExecutor ruleVersionExecutor(
            @Autowired(required = false)
            Map<String, com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator> conditionEvaluators) {
        return new TracingInterpretedExecutor(
                conditionEvaluators == null ? Map.of() : conditionEvaluators);
    }
}
