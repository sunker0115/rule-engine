package com.sstlfsj.rule.config.api.dto;

import com.sstlfsj.rule.kernel.api.model.ast.AndNode;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RuleVersionContentVOTest {

    @Test
    void accessorsRetainTypedContent() {
        AstNode ast = new AndNode(List.of(), null, null);
        RuleVersionContentVO vo = new RuleVersionContentVO(
                20L, 2L, "ACTIVE", "AST_BOOLEAN",
                ast, List.of(), List.of(), List.of("TXN"),
                null, null, "2026-06-16T00:00", "u1", "2026-06-16T01:00");

        assertThat(vo.ruleVersionId()).isEqualTo(20L);
        assertThat(vo.version()).isEqualTo(2L);
        assertThat(vo.status()).isEqualTo("ACTIVE");
        assertThat(vo.kind()).isEqualTo("AST_BOOLEAN");
        assertThat(vo.conditionAst()).isSameAs(ast);
        assertThat(vo.triggerEventTypes()).containsExactly("TXN");
        assertThat(vo.publishedBy()).isEqualTo("u1");
    }
}
