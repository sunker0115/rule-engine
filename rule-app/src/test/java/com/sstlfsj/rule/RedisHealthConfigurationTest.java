package com.sstlfsj.rule;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.health.DataRedisHealthContributorAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.health.DataRedisReactiveHealthContributorAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证真实应用配置中的 Redis 健康检查默认值与显式开启，保留 STREAM 所需客户端。 */
class RedisHealthConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(
                    DataRedisAutoConfiguration.class,
                    DataRedisReactiveAutoConfiguration.class,
                    DataRedisHealthContributorAutoConfiguration.class,
                    DataRedisReactiveHealthContributorAutoConfiguration.class));

    @Test
    void defaultConfigurationDoesNotRegisterRedisHealthCheck() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean("redisHealthContributor");
            assertThat(context).doesNotHaveBean("redisHealthIndicator");
            assertThat(context).hasSingleBean(StringRedisTemplate.class);
        });
    }

    @Test
    void explicitOptInRegistersRedisHealthCheck() {
        contextRunner.withPropertyValues("management.health.redis.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasBean("redisHealthContributor");
                    assertThat(context).hasSingleBean(StringRedisTemplate.class);
                });
    }
}
