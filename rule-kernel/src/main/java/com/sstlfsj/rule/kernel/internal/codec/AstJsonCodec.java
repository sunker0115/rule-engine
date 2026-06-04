package com.sstlfsj.rule.kernel.internal.codec;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ast.*;

import java.util.List;

/**
 * AST JSON 编解码器：配置 Jackson 多态反序列化，支持所有 AstNode 子类型。
 * 纯 Java，无 Spring 依赖；rule-sdk SnapshotPoller 和 rule-eval-svc SnapshotAssembler 均使用。
 */
public class AstJsonCodec {

    /**
     * 返回已配置多态 Mixin 的 ObjectMapper 新实例（ObjectMapper 线程安全，可复用）。
     *
     * @return 配置好的 ObjectMapper
     */
    public ObjectMapper createMapper() {
        return new ObjectMapper()
                .addMixIn(AstNode.class, AstNodeMixin.class)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /**
     * 将 JSON 字符串反序列化为 AstNode（多态，类型由 "type" 字段区分）。
     *
     * @param json AST JSON 字符串
     * @return 反序列化后的 AstNode
     */
    public AstNode deserializeAst(String json) throws JsonProcessingException {
        return createMapper().readValue(json, AstNode.class);
    }

    /**
     * 将 JSON 字符串反序列化为 PreGateConfig 列表。
     *
     * @param json Pre-Gate 配置 JSON 数组字符串
     * @return PreGateConfig 列表
     */
    public List<RuleVersionSnapshot.PreGateConfig> deserializePreGates(String json) throws JsonProcessingException {
        return createMapper().readValue(json, new TypeReference<>() {});
    }

    /**
     * 将 JSON 字符串反序列化为 DecisionBinding 列表。
     *
     * @param json 决策绑定 JSON 数组字符串
     * @return DecisionBinding 列表
     */
    public List<RuleVersionSnapshot.DecisionBinding> deserializeDecisionBindings(String json) throws JsonProcessingException {
        return createMapper().readValue(json, new TypeReference<>() {});
    }

    /**
     * 将 JSON 字符串反序列化为字符串列表（用于 triggerEventTypes 等）。
     *
     * @param json JSON 数组字符串
     * @return 字符串列表
     */
    public List<String> deserializeStringList(String json) throws JsonProcessingException {
        return createMapper().readValue(json, new TypeReference<>() {});
    }

    /** AstNode sealed 接口的 Jackson 多态 mixin，通过 "type" 字段区分子类型。 */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = AndNode.class,           name = "AndNode"),
            @JsonSubTypes.Type(value = OrNode.class,            name = "OrNode"),
            @JsonSubTypes.Type(value = NotNode.class,           name = "NotNode"),
            @JsonSubTypes.Type(value = ConditionNode.class,     name = "ConditionNode"),
            @JsonSubTypes.Type(value = ScorecardRootNode.class, name = "ScorecardRootNode"),
            @JsonSubTypes.Type(value = XorNode.class,           name = "XorNode"),
            @JsonSubTypes.Type(value = IfNode.class,            name = "IfNode"),
            @JsonSubTypes.Type(value = DecisionLeafNode.class,  name = "DecisionLeafNode"),
            @JsonSubTypes.Type(value = DecisionTableNode.class, name = "DecisionTableNode")
    })
    interface AstNodeMixin {}
}
