package com.sstlfsj.rule.eval.internal.snapshot;

import com.sstlfsj.rule.kernel.api.model.ast.AndNode;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.model.ast.DecisionLeafNode;
import com.sstlfsj.rule.kernel.api.model.ast.DecisionTableNode;
import com.sstlfsj.rule.kernel.api.model.ast.IfNode;
import com.sstlfsj.rule.kernel.api.model.ast.NotNode;
import com.sstlfsj.rule.kernel.api.model.ast.OrNode;
import com.sstlfsj.rule.kernel.api.model.ast.ScorecardRootNode;
import com.sstlfsj.rule.kernel.api.model.ast.XorNode;
import org.springframework.aot.hint.BindingReflectionHintsRegistrar;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

import java.util.List;

/**
 * 为 AST 节点的 Jackson 多态反序列化注册 native 反射元数据。
 *
 * <p>{@link AstNode} 用 {@code @JsonTypeInfo}/{@code @JsonSubTypes} 多态读写，native 下 Jackson 需
 * 每个具体子类型的构造器 / 访问器 / 字段反射元数据才能实例化，否则启动期 {@code IndexStartupLoader}
 * 载入 ACTIVE 规则、反序列化 {@code condition_ast} 时报缺 value instantiator。
 * {@link BindingReflectionHintsRegistrar} 会递归 record 组件，自动覆盖 {@code DecisionTableNode} 的
 * 嵌套 {@code Column}/{@code Row}，故只需注册 9 个顶层节点类型。
 */
class AstNativeHints implements RuntimeHintsRegistrar {

    private static final List<Class<?>> AST_TYPES = List.of(
            AndNode.class, OrNode.class, NotNode.class, ConditionNode.class, ScorecardRootNode.class,
            XorNode.class, IfNode.class, DecisionLeafNode.class, DecisionTableNode.class);

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        BindingReflectionHintsRegistrar binding = new BindingReflectionHintsRegistrar();
        for (Class<?> type : AST_TYPES) {
            binding.registerReflectionHints(hints.reflection(), type);
        }
    }
}
