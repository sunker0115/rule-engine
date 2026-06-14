package com.sstlfsj.rule.expression.groovy.starter;

import com.sstlfsj.rule.expression.groovy.GroovyExpressionEngine;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * 自动装配 Groovy 表达式引擎(EXPRESSION_SCRIPT 第六引擎)。
 * 引入本 starter 即注册 {@link GroovyExpressionEngine} bean;评估侧 ScriptExecutor 经
 * {@code List<ExpressionEngine>} 自动收集到此引擎,消费方无需硬编码实例化。
 * 纯引擎模块 rule-expression-groovy 保持无 Spring(故本类不在 kernel 包下);Spring 装配集中于此 starter。
 */
@AutoConfiguration
public class GroovyExpressionEngineAutoConfiguration {

    /**
     * 注册 Groovy 引擎(完整脚本语言 + groovy-sandbox deny-by-default 沙箱,线程安全单例)。
     * 业务方注册了自定义 {@link GroovyExpressionEngine} bean 时本 bean 不生效。
     *
     * @return GroovyExpressionEngine 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public GroovyExpressionEngine groovyExpressionEngine() {
        return new GroovyExpressionEngine();
    }
}
