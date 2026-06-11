package com.sstlfsj.rule.eval.snapshot;

import com.sstlfsj.rule.kernel.api.model.ast.AndNode;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.model.ast.DecisionLeafNode;
import com.sstlfsj.rule.kernel.api.model.ast.DecisionTableNode;
import com.sstlfsj.rule.kernel.api.model.ast.IfNode;
import com.sstlfsj.rule.kernel.api.model.ast.NotNode;
import com.sstlfsj.rule.kernel.api.model.ast.OrNode;
import com.sstlfsj.rule.kernel.api.model.ast.ScorecardRootNode;
import com.sstlfsj.rule.kernel.api.model.ast.XorNode;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;

import java.lang.reflect.Constructor;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 AstNativeHints 为全部 AST 节点类型注册了 native 反射元数据（含嵌套 record）。 */
class AstNativeHintsTest {

    private static RuntimeHints register() {
        RuntimeHints hints = new RuntimeHints();
        RuntimeHintsRegistrar registrar = newRegistrar();
        registrar.registerHints(hints, AstNativeHintsTest.class.getClassLoader());
        return hints;
    }

    // AstNativeHints 为包级私有，测试在另一包，按需反射构造
    private static RuntimeHintsRegistrar newRegistrar() {
        try {
            Class<?> clazz = Class.forName(
                    "com.sstlfsj.rule.eval.internal.snapshot.AstNativeHints");
            Constructor<?> ctor = clazz.getDeclaredConstructor();
            ctor.setAccessible(true);
            return (RuntimeHintsRegistrar) ctor.newInstance();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void registersReflectionHintsForAllAstNodeTypes() {
        RuntimeHints hints = register();

        List<Class<?>> astTypes = List.of(
                AndNode.class, OrNode.class, NotNode.class, ConditionNode.class, ScorecardRootNode.class,
                XorNode.class, IfNode.class, DecisionLeafNode.class, DecisionTableNode.class);

        for (Class<?> type : astTypes) {
            assertThat(RuntimeHintsPredicates.reflection().onType(type).test(hints))
                    .as("应为 AST 类型 %s 注册反射 hints", type.getSimpleName())
                    .isTrue();
        }
    }

    @Test
    void registersNestedRecordTypesOfDecisionTable() {
        RuntimeHints hints = register();

        // BindingReflectionHintsRegistrar 递归 record 组件，DecisionTableNode 的嵌套 Column/Row 应被覆盖
        assertThat(RuntimeHintsPredicates.reflection().onType(DecisionTableNode.Column.class).test(hints))
                .as("应递归注册 DecisionTableNode.Column").isTrue();
        assertThat(RuntimeHintsPredicates.reflection().onType(DecisionTableNode.Row.class).test(hints))
                .as("应递归注册 DecisionTableNode.Row").isTrue();
    }
}
