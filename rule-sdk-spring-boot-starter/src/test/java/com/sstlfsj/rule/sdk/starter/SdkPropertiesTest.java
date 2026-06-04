package com.sstlfsj.rule.sdk.starter;

import com.sstlfsj.rule.sdk.FetchMode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SdkPropertiesTest {

    @Test
    void defaults_areCorrect() {
        SdkProperties props = new SdkProperties();
        assertThat(props.getFetchMode()).isEqualTo(FetchMode.DECLARED);
        assertThat(props.getScenes()).isEmpty();
        assertThat(props.getPollInterval()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void setters_roundtrip() {
        SdkProperties props = new SdkProperties();
        props.setServerUrl("http://localhost:8080");
        props.setTenantId("t1");
        props.setFetchMode(FetchMode.ALL);
        props.setScenes(List.of("fraud", "payment"));
        props.setPollInterval(Duration.ofMinutes(1));

        assertThat(props.getServerUrl()).isEqualTo("http://localhost:8080");
        assertThat(props.getTenantId()).isEqualTo("t1");
        assertThat(props.getFetchMode()).isEqualTo(FetchMode.ALL);
        assertThat(props.getScenes()).containsExactly("fraud", "payment");
        assertThat(props.getPollInterval()).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void ruleFiles_defaultEmpty() {
        SdkProperties props = new SdkProperties();
        assertThat(props.getRuleFiles()).isEmpty();
    }

    @Test
    void ruleFiles_setter_roundtrip() {
        SdkProperties props = new SdkProperties();
        props.setRuleFiles(List.of("classpath:rules/a.json", "classpath:rules/b.json"));
        assertThat(props.getRuleFiles()).containsExactly("classpath:rules/a.json", "classpath:rules/b.json");
    }
}
