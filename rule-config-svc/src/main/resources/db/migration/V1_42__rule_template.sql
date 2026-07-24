-- 模板系统 V2：Platform 层独立子系统，核心表零污染
-- 1. tenant 加 type（区分 SYSTEM/STANDARD，不用 tenant_id=0 魔法值）
ALTER TABLE tenant
    ADD COLUMN type VARCHAR(16) NOT NULL DEFAULT 'STANDARD'
    COMMENT 'STANDARD=普通租户, SYSTEM=平台系统租户';

-- 2. SYSTEM tenant 初始化（模板归属）
INSERT INTO tenant (code, name, type, status, created_at, updated_at)
VALUES ('SYSTEM', '平台系统', 'SYSTEM', 'ACTIVE', CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3));

-- 3. rule_template 身份层（同 rule_definition）
CREATE TABLE rule_template (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id   BIGINT       NOT NULL COMMENT '所属租户；SYSTEM tenant = 平台级模板',
    code        VARCHAR(128) NOT NULL,
    name        VARCHAR(256) NOT NULL,
    description VARCHAR(1024) DEFAULT NULL,
    kind        VARCHAR(32)  NOT NULL COMMENT 'RuleKind 枚举',
    status      VARCHAR(16)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/PUBLISHED/DISABLED',
    created_by  VARCHAR(64)  DEFAULT NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by  VARCHAR(64)  DEFAULT NULL,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_tenant_code (tenant_id, code),
    KEY idx_tenant_status (tenant_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 4. rule_template_version 快照层（不可变，同 rule_version）
CREATE TABLE rule_template_version (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    template_id   BIGINT      NOT NULL COMMENT '→ rule_template.id',
    version       INT         NOT NULL COMMENT '同一模板内单调递增',
    body_skeleton JSON        NOT NULL COMMENT '合法 body，所有值位已填默认值，无 token',
    slots         JSON        NOT NULL COMMENT 'TemplateSlot[]',
    bindings      JSON        NOT NULL COMMENT 'SlotBinding[]',
    status        VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/PUBLISHED',
    created_by    VARCHAR(64) DEFAULT NULL,
    created_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_template_version (template_id, version)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 5. rule_template_instantiation 溯源（可删，删了核心零影响）
CREATE TABLE rule_template_instantiation (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    template_id         BIGINT   NOT NULL COMMENT '→ rule_template.id',
    template_version_id BIGINT   NOT NULL COMMENT '→ rule_template_version.id',
    template_version    INT      NOT NULL COMMENT '冗余版本号，便于查询',
    rule_definition_id  BIGINT   NOT NULL COMMENT '→ rule_definition.id',
    rule_version_id     BIGINT   NOT NULL COMMENT '→ rule_version.id',
    slot_values         JSON     NOT NULL COMMENT '实例化填值快照',
    instantiated_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    instantiated_by     VARCHAR(64) DEFAULT NULL,
    KEY idx_template_id (template_id),
    KEY idx_rule_version_id (rule_version_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 注意：不含 ALTER TABLE rule_version（核心表天然无模板列，flyway clean 重建）
