package com.sstlfsj.rule.config.internal.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.Jackson3TypeHandler;
import com.sstlfsj.rule.config.api.dto.PayloadFieldSpec;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

/** scene_payload_schema_history 表实体，记录 payloadSchema 每次变更前的快照（D13）。 */
@Getter
@Setter
@TableName(value = "scene_payload_schema_history", autoResultMap = true)
public class ScenePayloadSchemaHistory {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sceneId;
    /** 快照对应的 payloadSchema 版本号（变更前的版本，用于溯源）。 */
    private Integer version;
    /** payloadSchema 快照；JSON 列由 TypeHandler 转换。 */
    @TableField(value = "schema_json", typeHandler = Jackson3TypeHandler.class)
    private List<PayloadFieldSpec> schema;
    private String createdBy;
    private LocalDateTime createdAt;
}
