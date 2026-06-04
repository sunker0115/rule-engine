package com.sstlfsj.rule.config.internal.publish;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sstlfsj.rule.kernel.api.model.ast.*;
import org.springframework.stereotype.Component;

/** 负责 AstNode 与 JSON 字符串互转，用于 rule_version.condition_ast 存储。 */
@Component
public class AstSerializer {

    private final ObjectMapper mapper;

    public AstSerializer() {
        this.mapper = new ObjectMapper();
        // 使用 type 字段区分多态类型，仅作用于 AstNode 层级
        this.mapper.addMixIn(AstNode.class, AstNodeMixin.class);
    }

    /**
     * 将 AstNode 序列化为 JSON 字符串，含 type 字段便于反序列化。
     *
     * @param node 待序列化的 AST 节点
     * @return JSON 字符串
     */
    public String toJson(AstNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException("AST 序列化失败", e);
        }
    }

    /**
     * 将 JSON 字符串反序列化为 AstNode（含子树递归恢复）。
     *
     * @param json 由 {@link #toJson} 生成的 JSON 字符串
     * @return 反序列化后的 AstNode
     */
    public AstNode fromJson(String json) {
        try {
            return mapper.readValue(json, AstNode.class);
        } catch (Exception e) {
            throw new IllegalStateException("AST 反序列化失败: " + e.getMessage(), e);
        }
    }

    /** Jackson mixin：为 AstNode sealed interface 声明多态类型映射。 */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = AndNode.class,           name = "AndNode"),
        @JsonSubTypes.Type(value = OrNode.class,            name = "OrNode"),
        @JsonSubTypes.Type(value = NotNode.class,           name = "NotNode"),
        @JsonSubTypes.Type(value = ConditionNode.class,     name = "ConditionNode"),
        @JsonSubTypes.Type(value = IfNode.class,            name = "IfNode"),
        @JsonSubTypes.Type(value = DecisionLeafNode.class,  name = "DecisionLeafNode"),
        @JsonSubTypes.Type(value = DecisionTableNode.class, name = "DecisionTableNode")
    })
    interface AstNodeMixin {}
}
