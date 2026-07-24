package com.sstlfsj.rule.config.internal.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.Jackson3TypeHandler;
import com.sstlfsj.rule.config.api.dto.SlotBinding;
import com.sstlfsj.rule.config.api.dto.TemplateSlot;
import com.sstlfsj.rule.kernel.api.model.RuleBody;
import com.sstlfsj.rule.kernel.api.model.RuleKind;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/** rule_template 表实体。 */
@Getter
@Setter
@TableName(value = "rule_template", autoResultMap = true)
public class RuleTemplate {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private Long tenantId;
    private String name;
    private String description;
    private RuleKind kind;
    /** body 骨架：合法 body，可覆盖位置已填默认值，无 token。 */
    @TableField(typeHandler = Jackson3TypeHandler.class)
    private RuleBody bodySkeleton;
    /** Slot 定义列表。 */
    @TableField(typeHandler = Jackson3TypeHandler.class)
    private List<TemplateSlot> slots;
    /** slot→body 位置的显式绑定（sidecar，非 token）。 */
    @TableField(typeHandler = Jackson3TypeHandler.class)
    private List<SlotBinding> bindings;
    private Integer version;
    private RuleTemplateStatus status;
    private String createdBy;
    private java.time.LocalDateTime createdAt;
    private String updatedBy;
    private java.time.LocalDateTime updatedAt;
}
