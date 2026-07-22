package com.sstlfsj.rule.kernel.api.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * 规则判定主体的多态载体：三承载按 kind 三选一收敛为一个 sealed 类型。
 * {@link AstBody}（AST 系四 kind）/ {@link ScriptBody}（EXPRESSION_SCRIPT）/
 * {@link FlowBody}（DECISION_FLOW）三变体，与 kind 家族一致（发布期校验）。
 * Jackson 多态判别属性 {@code type}（值 == 简单类名），与内层 AstNode/FlowNode 的判别不同层。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = AstBody.class, name = "AstBody"),
        @JsonSubTypes.Type(value = ScriptBody.class, name = "ScriptBody"),
        @JsonSubTypes.Type(value = FlowBody.class, name = "FlowBody"),
})
public sealed interface RuleBody permits AstBody, ScriptBody, FlowBody {
}
