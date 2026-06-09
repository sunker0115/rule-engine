package com.sstlfsj.rule.config;

import com.sstlfsj.rule.config.internal.MetricProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/** 验证 ConfigAutoConfiguration 携带必要的 Spring 注解。 */
class ConfigAutoConfigurationTest {

    @Test
    void hasAutoConfigurationAnnotation() {
        assertNotNull(ConfigAutoConfiguration.class.getAnnotation(AutoConfiguration.class));
    }

    @Test
    void componentScanTargetsInternalPackage() {
        ComponentScan scan = ConfigAutoConfiguration.class.getAnnotation(ComponentScan.class);
        assertNotNull(scan);
        assertArrayEquals(new String[]{"com.sstlfsj.rule.config.internal"}, scan.value());
    }

    @Test
    void enablesMetricProperties() {
        EnableConfigurationProperties ann =
                ConfigAutoConfiguration.class.getAnnotation(EnableConfigurationProperties.class);
        assertNotNull(ann);
        assertTrue(Arrays.asList(ann.value()).contains(MetricProperties.class));
    }
}
