package com.sstlfsj.rule.config.internal.publish;

import com.sstlfsj.rule.kernel.api.model.ast.AndNode;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConditionParamValidatorTest {

    @Test
    void missingRequiredKey_throws() {
        AstNode ast = new ConditionNode("GT", "amount", null, Map.of("wrongkey", 100), 0.0);
        assertThatThrownBy(() -> ConditionParamValidator.validate(ast))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("GT")
                .hasMessageContaining("threshold");
    }

    @Test
    void presentRequiredKey_passes() {
        AstNode ast = new ConditionNode("GT", "amount", null, Map.of("threshold", 100), 0.0);
        assertThatCode(() -> ConditionParamValidator.validate(ast)).doesNotThrowAnyException();
    }

    @Test
    void unknownConditionType_passes() {
        AstNode ast = new ConditionNode("CUSTOM_OP", "x", null, Map.of(), 0.0);
        assertThatCode(() -> ConditionParamValidator.validate(ast)).doesNotThrowAnyException();
    }

    @Test
    void nestedAnd_validatesAllLeaves() {
        AstNode ast = new AndNode(List.of(
                new ConditionNode("GT", "amount", null, Map.of("threshold", 1), 0.0),
                new ConditionNode("MATCHES", "name", null, Map.of("wrongkey", "x"), 0.0)
        ), null, null);
        assertThatThrownBy(() -> ConditionParamValidator.validate(ast))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MATCHES")
                .hasMessageContaining("regex");
    }

    @Test
    void betweenMissingMax_throws() {
        AstNode ast = new ConditionNode("BETWEEN", "amount", null, Map.of("min", 1), 0.0);
        assertThatThrownBy(() -> ConditionParamValidator.validate(ast))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max");
    }
}
