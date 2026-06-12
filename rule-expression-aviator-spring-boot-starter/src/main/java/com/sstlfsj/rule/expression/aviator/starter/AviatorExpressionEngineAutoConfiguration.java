package com.sstlfsj.rule.expression.aviator.starter;

import com.sstlfsj.rule.expression.aviator.AviatorExpressionEngine;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * 自动装配 Aviator 表达式引擎(EXPRESSION_SCRIPT 第二引擎)。
 * 引入本 starter 即注册 {@link AviatorExpressionEngine} bean;评估侧 ScriptExecutor 经
 * {@code List<ExpressionEngine>} 自动收集到此引擎,消费方无需硬编码实例化。
 * 纯引擎模块 rule-expression-aviator 保持无 Spring(故本类不在 kernel 包下);Spring 装配集中于此 starter。
 */
@AutoConfiguration
public class AviatorExpressionEngineAutoConfiguration {

    /**
     * 注册 Aviator 引擎(弱类型 + Aviator 内建编译缓存,线程安全单例)。
     * 业务方注册了自定义 {@link AviatorExpressionEngine} bean 时本 bean 不生效。
     *
     * @return AviatorExpressionEngine 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public AviatorExpressionEngine aviatorExpressionEngine() {
        return new AviatorExpressionEngine();
    }
}
