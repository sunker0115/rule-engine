package com.sstlfsj.rule.kernel.internal.codec;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.sstlfsj.rule.kernel.api.model.MetricDependency;
import com.sstlfsj.rule.kernel.api.model.PayloadDependency;
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

    /**
     * 非 Spring 场景使用：创建容忍未知字段、且 JSON 缺失原始类型字段时回退默认值的 ObjectMapper。
     * FAIL_ON_NULL_FOR_PRIMITIVES 关闭，使手写规则 JSON 省略 version 时回退 0（与便利构造默认一致）。
     */
    public AstJsonCodec() {
        this(JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .build());
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
    public AstNode deserializeAst(String json) throws JacksonException {
        return mapper.readValue(json, AstNode.class);
    }

    /**
     * 将 JSON 字符串反序列化为 PreGateConfig 列表。
     *
     * @param json Pre-Gate 配置 JSON 数组字符串
     * @return PreGateConfig 列表
     */
    public List<RuleVersionSnapshot.PreGateConfig> deserializePreGates(String json) throws JacksonException {
        return mapper.readValue(json, new TypeReference<>() {});
    }

    /**
     * 将 JSON 字符串反序列化为 DecisionBinding 列表。
     *
     * @param json 决策绑定 JSON 数组字符串
     * @return DecisionBinding 列表
     */
    public List<RuleVersionSnapshot.DecisionBinding> deserializeDecisionBindings(String json) throws JacksonException {
        return mapper.readValue(json, new TypeReference<>() {});
    }

    /**
     * 将 JSON 字符串反序列化为字符串列表（用于 triggerEventTypes 等）。
     *
     * @param json JSON 数组字符串
     * @return 字符串列表
     */
    public List<String> deserializeStringList(String json) throws JacksonException {
        return mapper.readValue(json, new TypeReference<>() {});
    }

    /**
     * 将 JSON 字符串反序列化为 MetricDependency 列表（rule_version.metric_dependencies）。
     *
     * @param json metric 依赖 JSON 数组字符串，元素形如 {"metricCode":"x","metricVersion":1}
     * @return MetricDependency 列表
     */
    public List<MetricDependency> deserializeMetricDependencies(String json) throws JacksonException {
        return mapper.readValue(json, new TypeReference<>() {});
    }

    /**
     * 将 JSON 字符串反序列化为 PayloadDependency 列表（rule_version.payload_dependencies）。
     *
     * @param json payload 依赖 JSON 数组字符串，元素形如 {"name":"amount","dataType":"DECIMAL","required":true}
     * @return PayloadDependency 列表
     */
    public List<PayloadDependency> deserializePayloadDependencies(String json) throws JacksonException {
        return mapper.readValue(json, new TypeReference<>() {});
    }

}
