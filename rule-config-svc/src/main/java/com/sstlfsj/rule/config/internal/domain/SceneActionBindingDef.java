package com.sstlfsj.rule.config.internal.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** scene_action_binding 表实体（Scene 可用 actionType 白名单，uk = scene_id + action_type）。 */
@Getter
@Setter
@TableName("scene_action_binding")
public class SceneActionBindingDef {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sceneId;
    private String actionType;
    /** JSON 对象字符串，Scene 级默认参数；可空。 */
    private String defaultParams;
    /** JSON 对象字符串，Scene 级频控覆盖；可空。 */
    private String rateLimitOverride;
    private String createdBy;
    private java.time.LocalDateTime createdAt;
    private String updatedBy;
    private java.time.LocalDateTime updatedAt;
}
