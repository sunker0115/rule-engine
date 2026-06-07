package com.sstlfsj.rule.job.xxl;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 engine.rule.job.xxl 前缀绑定与默认值。 */
class XxlJobPropertiesTest {

    private XxlJobProperties bind(MockEnvironment env) {
        var sources = ConfigurationPropertySources.from(env.getPropertySources());
        return new Binder(sources).bind("engine.rule.job.xxl", XxlJobProperties.class).get();
    }

    @Test
    void bindsAdminAndExecutorFields() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("engine.rule.job.xxl.admin-addresses", "http://a/xxl-job-admin")
                .withProperty("engine.rule.job.xxl.appname", "rule-engine")
                .withProperty("engine.rule.job.xxl.access-token", "secret")
                .withProperty("engine.rule.job.xxl.admin-username", "admin")
                .withProperty("engine.rule.job.xxl.admin-password", "pwd");

        XxlJobProperties p = bind(env);

        assertThat(p.getAdminAddresses()).isEqualTo("http://a/xxl-job-admin");
        assertThat(p.getAppname()).isEqualTo("rule-engine");
        assertThat(p.getAccessToken()).isEqualTo("secret");
        assertThat(p.getAdminUsername()).isEqualTo("admin");
        assertThat(p.getAdminPassword()).isEqualTo("pwd");
    }

    @Test
    void appliesDefaults() {
        XxlJobProperties p = bind(new MockEnvironment()
                .withProperty("engine.rule.job.xxl.admin-addresses", "http://a/xxl-job-admin"));

        assertThat(p.getPort()).isEqualTo(9999);
        assertThat(p.getLogRetentionDays()).isEqualTo(30);
        assertThat(p.isEnabled()).isTrue();
    }
}
