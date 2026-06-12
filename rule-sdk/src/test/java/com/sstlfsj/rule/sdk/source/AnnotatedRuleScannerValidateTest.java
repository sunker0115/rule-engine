package com.sstlfsj.rule.sdk.source;

import com.sstlfsj.rule.kernel.api.annotation.RuleDef;
import com.sstlfsj.rule.sdk.FactResolver;
import com.sstlfsj.rule.sdk.annotation.Condition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class AnnotatedRuleScannerValidateTest {

    @RuleDef(code = "bad-param", sceneCode = "demo")
    static class UnannotatedParamRule {
        @Condition
        public boolean c(Integer noAnnotation) { return true; }
    }

    @Test
    void scan_rejectsUnannotatedConditionParam_atStartup() {
        assertThatThrownBy(() ->
                new AnnotatedRuleScanner(new FactResolver(), "t").scan(List.of(new UnannotatedParamRule())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("@Fact");
    }
}
