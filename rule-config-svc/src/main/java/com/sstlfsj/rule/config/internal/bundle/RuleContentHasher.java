package com.sstlfsj.rule.config.internal.bundle;

import com.sstlfsj.rule.config.api.dto.RuleBundle;
import com.sstlfsj.rule.kernel.api.model.RuleBody;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.DecisionBinding;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.PreGateConfig;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则内容 SHA-256 哈希工具。
 *
 * <p>hash 以固定字段顺序序列化规则内容（body / bindings / preGates / kind /
 * triggerEventTypes），保证字段顺序与 null/空集合语义稳定——相同内容的两份规则总得到相同 hash。
 * body 为多态载体（AstBody/ScriptBody/FlowBody，含 type 判别），不同规则 body 不同则 hash 不同。
 * 用于 import 幂等判断（hash 相同 → 内容等价 → 无需建新版本）。</p>
 */
public final class RuleContentHasher {

    private RuleContentHasher() {}

    /**
     * 计算单条规则内容的 SHA-256 hex。
     *
     * @param body     判定主体多态载体（AstBody/ScriptBody/FlowBody；含 type 判别，进 hash 保证不同载体不撞）
     * @param bindings 决策绑定列表（null 视为空列表）
     * @param gates    Pre-Gate 列表（null 视为空列表）
     * @param kind     规则类型标签（null 视为 AST_BOOLEAN）
     * @param triggers 触发事件类型（null 视为空列表）
     * @param om       Jackson ObjectMapper（用于 typed 对象序列化）
     * @return SHA-256 hex 字符串（64 位小写）
     */
    public static String ruleHash(RuleBody body, List<DecisionBinding> bindings, List<PreGateConfig> gates,
                                   String kind, List<String> triggers, ObjectMapper om) {
        try {
            // 固定字段顺序构建规范化 Map
            Map<String, Object> canonical = new LinkedHashMap<>();
            canonical.put("kind", kind != null ? kind : "AST_BOOLEAN");
            canonical.put("body", body);  // 多态载体含 type 判别，null 时序列化为 null，语义稳定
            canonical.put("decisionBindings", bindings != null ? bindings : List.of());
            canonical.put("preGates", gates != null ? gates : List.of());
            canonical.put("triggerEventTypes", triggers != null ? triggers : List.of());

            String json = om.writeValueAsString(canonical);
            return sha256Hex(json);
        } catch (Exception e) {
            throw new IllegalStateException("规则内容 hash 计算失败", e);
        }
    }

    /**
     * 计算整 Bundle 的 revision（对所有 RuleEntry.contentHash 拼接再 SHA-256，不含依赖快照避免噪音）。
     * 每条规则的 contentHash 须已由 {@link #ruleHash} 算好。
     */
    public static String bundleRevision(RuleBundle bundle) {
        StringBuilder sb = new StringBuilder();
        for (RuleBundle.RuleEntry rule : bundle.rules()) {
            sb.append(rule.contentHash() != null ? rule.contentHash() : "");
            sb.append('|');
        }
        return sha256Hex(sb.toString());
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
