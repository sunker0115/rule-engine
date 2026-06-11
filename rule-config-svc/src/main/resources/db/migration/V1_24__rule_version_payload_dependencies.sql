-- 场景输入参数清单:规则引用的 payload 字段依赖(发布期从 scene.payloadSchema 冻结)
-- 与 metric_dependencies 对称;typed JSON 列,随 RuleVersionSnapshot 下发,评估期据此校验入参
ALTER TABLE rule_version
    ADD COLUMN payload_dependencies JSON NOT NULL
        COMMENT 'AST 引用的 payload 字段依赖 [{name,dataType,required}]'
        AFTER metric_dependencies;
