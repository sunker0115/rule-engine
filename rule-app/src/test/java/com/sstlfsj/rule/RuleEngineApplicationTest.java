package com.sstlfsj.rule;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import static org.junit.jupiter.api.Assertions.*;

class RuleEngineApplicationTest {

    @Test
    void hasSpringBootApplicationAnnotation() {
        SpringBootApplication ann = RuleEngineApplication.class.getAnnotation(SpringBootApplication.class);
        assertNotNull(ann);
    }

    @Test
    void isInRootPackage() {
        // Modulith 要求主类在所有业务模块的公共父包 com.sstlfsj.rule
        assertEquals("com.sstlfsj.rule", RuleEngineApplication.class.getPackageName());
    }
}
