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
 * Spring 场景可通过 {@link #AstJsonCodec(ObjectMapper)} 注入全局 Bean，非 Spring 场景用无参构造。
 */
public class AstJsonCodec {

    private final ObjectMapper mapper;

    /** 非 Spring 场景使用：创建仅禁用未知字段报错的默认 ObjectMapper。 */
    public AstJsonCodec() {
        this(new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES));
    }

    /**
     * Spring 场景使用：注入已配置好的全局 ObjectMapper（如 Spring Boot 自动配置的 Bean）。
     *
     * @param mapper 外部传入的 ObjectMapper
     */
    public AstJsonCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 返回当前持有的 ObjectMapper 实例。
     *
     * @return ObjectMapper
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
