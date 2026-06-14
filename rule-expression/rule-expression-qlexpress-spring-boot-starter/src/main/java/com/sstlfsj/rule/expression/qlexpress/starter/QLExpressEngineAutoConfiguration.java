package com.sstlfsj.rule.expression.qlexpress.starter;

import com.sstlfsj.rule.expression.qlexpress.QLExpressEngine;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * 自动装配 QLExpress 表达式引擎(EXPRESSION_SCRIPT 第三引擎)。
 * 引入本 starter 即注册 {@link QLExpressEngine} bean;评估侧 ScriptExecutor 经
 * {@code List<ExpressionEngine>} 自动收集到此引擎,消费方无需硬编码实例化。
 * 纯引擎模块 rule-expression-qlexpress 保持无 Spring(故本类不在 kernel 包下);Spring 装配集中于此 starter。
 */
@AutoConfiguration
public class QLExpressEngineAutoConfiguration {

    /**
     * 注册 QLExpress 引擎(弱类型 + QLExpress 内建编译缓存,线程安全单例)。
     * 业务方注册了自定义 {@link QLExpressEngine} bean 时本 bean 不生效。
     *
     * @return QLExpressEngine 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public QLExpressEngine qlExpressEngine() {
        return new QLExpressEngine();
    }
}
