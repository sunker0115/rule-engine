package com.sstlfsj.rule.config.internal.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** scene 表实体，对应 05-storage.md §3.1 scene DDL。 */
@Getter
@Setter
@TableName("scene")
public class SceneDef {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String code;
    private String name;
    private String description;
    private String dominantMode;
    private String decisionStrategy;
    private String subjectType;
    /** JSON 数组字符串，存储允许的 eventType 白名单。 */
    private String eventTypes;
    /** JSON 对象字符串，存储 payloadSchema 字段类型声明。 */
    private String payloadSchema;
    /** JSON 对象字符串，存储 Scene 默认参数。 */
    private String defaultParams;
    private String status;
    private String createdBy;
    private java.time.LocalDateTime createdAt;
    private String updatedBy;
    private java.time.LocalDateTime updatedAt;
}
