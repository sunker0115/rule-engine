package com.sstlfsj.rule.expression.cel.starter;

import com.sstlfsj.rule.kernel.expression.cel.CelExpressionEngine;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * 自动装配 CEL 表达式引擎(EXPRESSION_SCRIPT 默认引擎)。
 * 引入本 starter 即注册 {@link CelExpressionEngine} bean;评估侧 ScriptExecutor 经
 * {@code List<ExpressionEngine>} 自动收集到此引擎,消费方无需硬编码实例化。
 * 纯引擎模块 rule-kernel-expression-cel 保持无 Spring(故本类不在 kernel 包下);Spring 装配集中于此 starter。
 */
@AutoConfiguration
public class CelExpressionEngineAutoConfiguration {

    /**
     * 注册 CEL 引擎(dyn env + Caffeine 预编译缓存,线程安全单例)。
     * 业务方注册了自定义 {@link CelExpressionEngine} bean 时本 bean 不生效。
     *
     * @return CelExpressionEngine 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public CelExpressionEngine celExpressionEngine() {
        return new CelExpressionEngine();
    }
}
