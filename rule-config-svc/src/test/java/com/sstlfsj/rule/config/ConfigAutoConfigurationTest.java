package com.sstlfsj.rule.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies ConfigAutoConfiguration carries the required Spring annotations. */
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
}
