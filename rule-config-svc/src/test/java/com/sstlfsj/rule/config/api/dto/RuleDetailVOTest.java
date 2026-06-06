package com.sstlfsj.rule.config.api.dto;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuleDetailVOTest {

    @Test
    void 暴露各字段() {
        RuleDetailVO vo = new RuleDetailVO(10L, "rule.a", "规则A", "PUBLISHED", "AST_BOOLEAN",
                "risk.transfer", Map.of("type", "AndNode"), List.of(), 42L);
        assertThat(vo.ruleDefinitionId()).isEqualTo(10L);
        assertThat(vo.code()).isEqualTo("rule.a");
        assertThat(vo.sceneCode()).isEqualTo("risk.transfer");
        assertThat(vo.currentVersionId()).isEqualTo(42L);
        assertThat(vo.conditionAst()).isInstanceOf(Map.class);
    }
}
