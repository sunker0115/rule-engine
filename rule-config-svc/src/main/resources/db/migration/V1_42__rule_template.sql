-- D74: 参数化规则模板（authoring 便利层，非运行时概念）
CREATE TABLE rule_template
(
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    code          VARCHAR(64)  NOT NULL COMMENT '模板编码，租户内唯一',
    tenant_id     BIGINT       NOT NULL DEFAULT 0,
    name          VARCHAR(128) NOT NULL COMMENT '模板名称',
    description   VARCHAR(512) DEFAULT NULL,
    kind          VARCHAR(32)  NOT NULL COMMENT 'RuleKind（覆盖全 6 kind：AST 四 kind + EXPRESSION_SCRIPT + DECISION_FLOW）',
    body_skeleton JSON         NOT NULL COMMENT '合法 body 骨架，可覆盖位置默认值就位，无 token',
    slots         JSON         NOT NULL COMMENT 'TemplateSlot[] 参数 schema：[{key,label,dataType,required,constraint}]',
    bindings      JSON         NOT NULL COMMENT 'SlotBinding[]：slot→body 位置显式绑定（JsonPointer sidecar）',
    version       INT          NOT NULL DEFAULT 1 COMMENT '模板版本号，发布时 +1',
    status        VARCHAR(16)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/PUBLISHED/DISABLED',
    created_by    VARCHAR(64)  DEFAULT NULL,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by    VARCHAR(64)  DEFAULT NULL,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_tenant_code (tenant_id, code),
    KEY idx_tenant_status (tenant_id, status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- 实例化来源模板溯源（无 FK——模板升版不回溯老实例）
ALTER TABLE rule_version
    ADD COLUMN template_id      BIGINT DEFAULT NULL COMMENT '实例化来源模板 ID（手建规则为 NULL）',
    ADD COLUMN template_version INT    DEFAULT NULL COMMENT '实例化时模板版本号';
