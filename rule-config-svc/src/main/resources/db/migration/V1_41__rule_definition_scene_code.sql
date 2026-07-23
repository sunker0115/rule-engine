-- cross-scene-rule-ref: rule_definition 去 scene_id，改用 scene_code 业务标识
-- code 在 tenant 内已由 uk_tenant_code(tenant_id, code) 保证唯一，scene_code 仅作归属标识与过滤索引
ALTER TABLE rule_definition
    DROP KEY idx_scene_id,
    DROP COLUMN scene_id,
    ADD COLUMN scene_code VARCHAR(64) NOT NULL DEFAULT '' COMMENT '关联 scene.code，业务标识',
    ADD KEY idx_tenant_scene (tenant_id, scene_code);
