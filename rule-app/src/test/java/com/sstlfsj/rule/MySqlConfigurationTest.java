package com.sstlfsj.rule;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.util.ClassUtils;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证实际默认配置选用 MySQL，且测试/运行依赖不再夹带 H2。 */
class MySqlConfigurationTest {

    @Test
    void defaultDataSourcesUseMySql() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var environment = context.getEnvironment();
                    assertThat(environment.getProperty("spring.datasource.driver-class-name"))
                            .isEqualTo("com.mysql.cj.jdbc.Driver");
                    assertThat(environment.getProperty("spring.datasource.url")).startsWith("jdbc:mysql:");
                    assertThat(environment.getProperty("engine.rule.fetch.datasources[0].url"))
                            .startsWith("jdbc:mysql:");
                });
    }

    @Test
    void classpathDoesNotContainH2() {
        assertThat(ClassUtils.isPresent("org.h2.Driver", getClass().getClassLoader())).isFalse();
    }
}
