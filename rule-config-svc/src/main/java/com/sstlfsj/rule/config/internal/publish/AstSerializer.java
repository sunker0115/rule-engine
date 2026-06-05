package com.sstlfsj.rule.config.internal.publish;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;
import com.sstlfsj.rule.kernel.internal.codec.AstJsonCodec;
import org.springframework.stereotype.Component;

/**
 * 负责 AstNode 与 JSON 字符串互转，用于 rule_version.condition_ast 存储。
 * 委托 rule-kernel 的 {@link AstJsonCodec} 构建 ObjectMapper，保持两侧类型注册一致。
 * ObjectMapper 线程安全，构造后缓存复用。
 */
@Component
public class AstSerializer {

    private final ObjectMapper mapper = new AstJsonCodec().createMapper();

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
}
