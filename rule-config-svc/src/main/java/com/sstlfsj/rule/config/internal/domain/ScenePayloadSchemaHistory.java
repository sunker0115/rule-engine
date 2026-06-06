package com.sstlfsj.rule.config.internal.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** scene_payload_schema_history 表实体，记录 payloadSchema 每次变更前的快照（D13）。 */
@Getter
@Setter
@TableName("scene_payload_schema_history")
public class ScenePayloadSchemaHistory {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sceneId;
    /** 快照对应的 payloadSchema 版本号（变更前的版本，用于溯源）。 */
    private Integer version;
    /** payloadSchema JSON 数组字符串快照；调用方负责序列化，直接存入原始 JSON 字符串。 */
    private String schemaJson;
    private String createdBy;
    private LocalDateTime createdAt;
}
