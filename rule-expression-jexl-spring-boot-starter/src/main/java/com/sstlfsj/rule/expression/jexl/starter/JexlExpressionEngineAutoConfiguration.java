package com.sstlfsj.rule.expression.jexl.starter;

import com.sstlfsj.rule.expression.jexl.JexlExpressionEngine;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * 自动装配 JEXL 表达式引擎(EXPRESSION_SCRIPT 第五引擎)。
 * 引入本 starter 即注册 {@link JexlExpressionEngine} bean;评估侧 ScriptExecutor 经
 * {@code List<ExpressionEngine>} 自动收集到此引擎,消费方无需硬编码实例化。
 * 纯引擎模块 rule-expression-jexl 保持无 Spring(故本类不在 kernel 包下);Spring 装配集中于此 starter。
 */
@AutoConfiguration
public class JexlExpressionEngineAutoConfiguration {

    /**
     * 注册 JEXL 引擎(弱类型 + RESTRICTED 沙箱,线程安全单例)。
     * 业务方注册了自定义 {@link JexlExpressionEngine} bean 时本 bean 不生效。
     *
     * @return JexlExpressionEngine 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public JexlExpressionEngine jexlExpressionEngine() {
        return new JexlExpressionEngine();
    }
}
