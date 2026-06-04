package com.sstlfsj.rule.eval.internal.snapshot;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ast.*;
import org.springframework.stereotype.Component;

import java.util.List;

/** 负责 AstNode 及 RuleVersionSnapshot 子结构的 JSON 反序列化，使用 Jackson mixin 处理 sealed 接口多态。 */
@Component
public class AstJsonCodec {

    private final ObjectMapper mapper;

    public AstJsonCodec() {
        this.mapper = new ObjectMapper()
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
        return mapper.readValue(json, AstNode.class);
    }

    /**
     * 将 JSON 字符串反序列化为 PreGateConfig 列表。
     *
     * @param json Pre-Gate 配置 JSON 数组字符串
     * @return PreGateConfig 列表
     */
    public List<RuleVersionSnapshot.PreGateConfig> deserializePreGates(String json) throws JsonProcessingException {
        return mapper.readValue(json, new TypeReference<>() {});
    }

    /**
     * 将 JSON 字符串反序列化为 DecisionBinding 列表。
     *
     * @param json 决策绑定 JSON 数组字符串
     * @return DecisionBinding 列表
     */
    public List<RuleVersionSnapshot.DecisionBinding> deserializeDecisionBindings(String json) throws JsonProcessingException {
        return mapper.readValue(json, new TypeReference<>() {});
    }

    /**
     * 将 JSON 字符串反序列化为字符串列表（用于 triggerEventTypes 等）。
     *
     * @param json JSON 数组字符串
     * @return 字符串列表
     */
    public List<String> deserializeStringList(String json) throws JsonProcessingException {
        return mapper.readValue(json, new TypeReference<>() {});
    }

    /** AstNode sealed 接口的 Jackson 多态 mixin，通过 "type" 字段区分子类型。 */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = AndNode.class,           name = "AndNode"),
            @JsonSubTypes.Type(value = OrNode.class,            name = "OrNode"),
            @JsonSubTypes.Type(value = NotNode.class,           name = "NotNode"),
            @JsonSubTypes.Type(value = ConditionNode.class,     name = "ConditionNode"),
            @JsonSubTypes.Type(value = ScorecardRootNode.class, name = "ScorecardRootNode")
    })
    interface AstNodeMixin {}
}
