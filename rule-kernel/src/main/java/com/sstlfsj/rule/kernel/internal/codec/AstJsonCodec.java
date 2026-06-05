package com.sstlfsj.rule.kernel.internal.codec;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;

import java.util.List;

/**
 * AST JSON 编解码器：AstNode 多态配置已直接标注在接口上，此处仅封装常用反序列化方法。
 * 纯 Java，无 Spring 依赖；rule-sdk SnapshotPoller 和 rule-eval-svc SnapshotAssembler 均使用。
 */
public class AstJsonCodec {

    private final ObjectMapper mapper = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /**
     * 返回配置好的 ObjectMapper（忽略未知字段；AstNode 多态由接口注解处理，无需 mixin）。
     *
     * @return 配置好的 ObjectMapper
     */
    public ObjectMapper createMapper() {
        return mapper;
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

}
