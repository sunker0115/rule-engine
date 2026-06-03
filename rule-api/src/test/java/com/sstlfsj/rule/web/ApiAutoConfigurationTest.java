package com.sstlfsj.rule.web;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

import static org.junit.jupiter.api.Assertions.*;

/** 验证 ApiAutoConfiguration 具备正确的注解配置。 */
class ApiAutoConfigurationTest {

    @Test
    void hasAutoConfigurationAnnotation() {
        assertNotNull(ApiAutoConfiguration.class.getAnnotation(AutoConfiguration.class));
    }

    @Test
    void componentScanCoversWebPackage() {
        ComponentScan scan = ApiAutoConfiguration.class.getAnnotation(ComponentScan.class);
        assertNotNull(scan);
        boolean coversWeb = false;
        for (String pkg : scan.value()) {
            if (pkg.startsWith("com.sstlfsj.rule.web")) {
                coversWeb = true;
                break;
            }
        }
        assertTrue(coversWeb, "ComponentScan 应覆盖 com.sstlfsj.rule.web");
    }
}
