package com.sstlfsj.rule.config.internal.template;

import com.sstlfsj.rule.config.api.dto.JsonPointerTarget;
import com.sstlfsj.rule.config.api.dto.SlotBinding;
import com.sstlfsj.rule.config.api.dto.TemplateSlot;
import com.sstlfsj.rule.kernel.api.model.AstBody;
import com.sstlfsj.rule.kernel.api.model.FlowBody;
import com.sstlfsj.rule.kernel.api.model.RuleBody;
import com.sstlfsj.rule.kernel.api.model.ScriptBody;
import org.springframework.stereotype.Component;
import tools.jackson.core.JsonPointer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 用 Jackson JsonPointer 寻址任意 body JSON 树，覆盖全部 Ast/Script/Flow 三种 body 变体（对应全 6 kind）。
 * bind = body→JsonNode 树 → 按 binding 逐 pointer 替换 → 树→RuleBody；validate = pointer 可解析 +
 * slot↔binding 1:1 + body 专属守卫。
 */
@Component
public class JsonPointerBinder implements TemplateBinder {

    private static final String SCRIPT_PARAMS_PREFIX = "/script/params/";

    private final ObjectMapper objectMapper;

    public JsonPointerBinder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(RuleBody body) {
        return body instanceof AstBody || body instanceof ScriptBody || body instanceof FlowBody;
    }

    @Override
    public void validate(RuleBody skeleton, List<SlotBinding> bindings, List<TemplateSlot> slots) {
        // 1. body 专属守卫（脚本仅 /script/params/*、flow 禁 /referencedSnapshots）
        guardBodySpecific(skeleton, bindings);
        // 2. pointer 可解析到 skeleton 中已存在节点 + slot key 无重复
        JsonNode tree = objectMapper.convertValue(skeleton, JsonNode.class);
        Set<String> bindingSlots = new HashSet<>();
        for (SlotBinding b : bindings) {
            if (!bindingSlots.add(b.slotKey())) {
                throw new IllegalArgumentException("TEMPLATE_SLOT_BINDING_MISMATCH: slot key 重复: " + b.slotKey());
            }
            if (b.target() instanceof JsonPointerTarget jpt) {
                JsonNode target = tree.at(JsonPointer.compile(jpt.jsonPointer()));
                if (target.isMissingNode()) {
                    throw new IllegalArgumentException(
                            "TEMPLATE_BINDING_UNRESOLVABLE: pointer 解析失败: " + jpt.jsonPointer());
                }
            }
        }
        // 3. slots 自身 key 无重复
        Set<String> slotKeys = new HashSet<>();
        if (slots != null) {
            for (TemplateSlot s : slots) {
                if (!slotKeys.add(s.key())) {
                    throw new IllegalArgumentException("TEMPLATE_SLOT_BINDING_MISMATCH: slot key 重复: " + s.key());
                }
            }
        }
        // 4. slot↔binding 1:1 双射
        if (!bindingSlots.equals(slotKeys)) {
            Set<String> missing = new HashSet<>(slotKeys);
            missing.removeAll(bindingSlots);
            Set<String> extra = new HashSet<>(bindingSlots);
            extra.removeAll(slotKeys);
            throw new IllegalArgumentException("TEMPLATE_SLOT_BINDING_MISMATCH"
                    + (missing.isEmpty() ? "" : "，缺少 binding: " + missing)
                    + (extra.isEmpty() ? "" : "，多余 binding: " + extra));
        }
    }

    @Override
    public RuleBody bind(RuleBody skeleton, List<SlotBinding> bindings, Map<String, Object> values) {
        JsonNode tree = objectMapper.convertValue(skeleton, JsonNode.class);
        for (SlotBinding b : bindings) {
            // 省略的可选 slot（values 无该键）→ 保留 skeleton 默认值，不用 null 覆盖
            if (!values.containsKey(b.slotKey())) continue;
            if (b.target() instanceof JsonPointerTarget jpt) {
                JsonPointer pointer = JsonPointer.compile(jpt.jsonPointer());
                JsonNode parentNode = tree.at(pointer.head());
                JsonPointer last = pointer.last();
                JsonNode valueNode = objectMapper.convertValue(values.get(b.slotKey()), JsonNode.class);
                if (parentNode instanceof ObjectNode parent) {
                    // 对象属性位：末段为属性名
                    parent.set(last.getMatchingProperty(), valueNode);
                } else if (parentNode instanceof ArrayNode array) {
                    // 数组元素位（如 DecisionTable Row.conditions[i]）：末段为下标
                    int idx = last.getMatchingIndex();
                    if (idx >= 0 && idx < array.size()) {
                        array.set(idx, valueNode);
                    }
                }
            }
        }
        return objectMapper.convertValue(tree, RuleBody.class);
    }

    /** body 专属守卫：Script 仅允许指向 /script/params/*，Flow 禁指向 /referencedSnapshots。 */
    private void guardBodySpecific(RuleBody body, List<SlotBinding> bindings) {
        if (body instanceof ScriptBody) {
            for (SlotBinding b : bindings) {
                if (b.target() instanceof JsonPointerTarget jpt) {
                    String path = jpt.jsonPointer();
                    if (!(path.startsWith(SCRIPT_PARAMS_PREFIX) && path.length() > SCRIPT_PARAMS_PREFIX.length())) {
                        throw new IllegalArgumentException(
                                "TEMPLATE_TARGET_FORBIDDEN: Script 仅允许 /script/params/*: " + path);
                    }
                }
            }
        }
        if (body instanceof FlowBody) {
            for (SlotBinding b : bindings) {
                if (b.target() instanceof JsonPointerTarget jpt
                        && jpt.jsonPointer().startsWith("/referencedSnapshots")) {
                    throw new IllegalArgumentException(
                            "TEMPLATE_TARGET_FORBIDDEN: 不可指向 /referencedSnapshots: " + jpt.jsonPointer());
                }
            }
        }
    }
}
