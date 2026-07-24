package com.sstlfsj.rule.config.internal.template;

import com.sstlfsj.rule.config.api.dto.SlotBinding;
import com.sstlfsj.rule.config.api.dto.TemplateSlot;
import com.sstlfsj.rule.kernel.api.model.RuleBody;

import java.util.List;
import java.util.Map;

/**
 * 模板绑定器 SPI：将 instance 填值按 binding 声明写入 body skeleton。
 * 实现按 {@link #supports(RuleBody)} 判别 body 变体，Spring 注入 {@code List<TemplateBinder>} 分派。
 */
public interface TemplateBinder {

    /**
     * 是否处理该 body 变体。
     *
     * @param body 模板 body skeleton
     * @return true 表示本 binder 负责该变体
     */
    boolean supports(RuleBody body);

    /**
     * 校验 bindings 与 skeleton 的一致性：每个 target 在 skeleton 中可解析到已存在节点、
     * slot→binding 1:1 双射、slot key 无重复、body 专属守卫（如 script 仅允许 /script/params/*）；
     * 不符抛带错误码的 {@link IllegalArgumentException}。
     *
     * @param skeleton body 骨架
     * @param bindings slot→body 位置绑定
     * @param slots    slot 定义列表
     */
    void validate(RuleBody skeleton, List<SlotBinding> bindings, List<TemplateSlot> slots);

    /**
     * 按 bindings 把 values 填入 skeleton 对应位置，返回新 body（不改入参）。
     *
     * @param skeleton body 骨架
     * @param bindings slot→body 位置绑定
     * @param values   slotKey→value，已通过 required/type/constraint 校验
     * @return 填值后的新 body
     */
    RuleBody bind(RuleBody skeleton, List<SlotBinding> bindings, Map<String, Object> values);
}
