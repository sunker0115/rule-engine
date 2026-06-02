package com.sstlfsj.rule.app;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import static org.junit.jupiter.api.Assertions.*;

class RuleEngineApplicationTest {

    @Test
    void hasSpringBootApplicationAnnotation() {
        SpringBootApplication ann = RuleEngineApplication.class.getAnnotation(SpringBootApplication.class);
        assertNotNull(ann);
        // 确认扫描根包正确，避免漏扫子模块
        assertArrayEquals(new String[]{"com.sstlfsj.rule"}, ann.scanBasePackages());
    }
}
