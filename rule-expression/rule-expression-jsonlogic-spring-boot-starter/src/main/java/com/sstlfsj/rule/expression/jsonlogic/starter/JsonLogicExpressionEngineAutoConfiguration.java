package com.sstlfsj.rule.expression.jsonlogic.starter;

import com.sstlfsj.rule.expression.jsonlogic.JsonLogicExpressionEngine;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * 自动装配 JsonLogic 表达式引擎(EXPRESSION_SCRIPT 第四引擎)。
 * 引入本 starter 即注册 {@link JsonLogicExpressionEngine} bean;评估侧 ScriptExecutor 经
 * {@code List<ExpressionEngine>} 自动收集到此引擎,消费方无需硬编码实例化。
 * 纯引擎模块 rule-expression-jsonlogic 保持无 Spring(故本类不在 kernel 包下);Spring 装配集中于此 starter。
 */
@AutoConfiguration
public class JsonLogicExpressionEngineAutoConfiguration {

    /**
     * 注册 JsonLogic 引擎(纯 JSON 数据驱动、天然 safe-by-design,线程安全单例)。
     * 业务方注册了自定义 {@link JsonLogicExpressionEngine} bean 时本 bean 不生效。
     *
     * @return JsonLogicExpressionEngine 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public JsonLogicExpressionEngine jsonLogicExpressionEngine() {
        return new JsonLogicExpressionEngine();
    }
}
