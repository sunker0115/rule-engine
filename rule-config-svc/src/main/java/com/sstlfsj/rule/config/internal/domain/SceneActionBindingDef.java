package com.sstlfsj.rule.config.internal.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.Jackson3TypeHandler;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

/** scene_action_binding 表实体（Scene 可用 actionType 白名单，uk = scene_id + action_type）。 */
@Getter
@Setter
@TableName(value = "scene_action_binding", autoResultMap = true)
public class SceneActionBindingDef {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sceneId;
    private String actionType;
    /** Scene 级默认参数（依 actionType 异构，故为开放 Map）；JSON 列由 TypeHandler 转换，可空。 */
    @TableField(typeHandler = Jackson3TypeHandler.class)
    private Map<String, Object> defaultParams;
    private String createdBy;
    private java.time.LocalDateTime createdAt;
    private String updatedBy;
    private java.time.LocalDateTime updatedAt;
}
