package com.sstlfsj.rule.sdk.starter;

import com.sstlfsj.rule.sdk.RuleEngineClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/** 自动装配 RuleEngineClient，读取 rule.sdk.* 配置。 */
@AutoConfiguration
@EnableConfigurationProperties(SdkProperties.class)
public class RuleEngineClientAutoConfiguration {

    /**
     * 注册 RuleEngineClient Bean。
     * 业务方注册了自定义 RuleEngineClient Bean 时此 Bean 不生效。
     *
     * @param props rule.sdk.* 配置
     * @return RuleEngineClient 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public RuleEngineClient ruleEngineClient(SdkProperties props) {
        RuleEngineClient.Builder builder = RuleEngineClient.builder()
                .serverUrl(props.getServerUrl())
                .tenantId(props.getTenantId())
                .fetchMode(props.getFetchMode())
                .pollInterval(props.getPollInterval());
        if (props.getScenes() != null) {
            props.getScenes().forEach(builder::scenes);
        }
        return builder.build();
    }
}
