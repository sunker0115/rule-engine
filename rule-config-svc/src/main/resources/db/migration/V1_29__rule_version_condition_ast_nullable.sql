-- EXPRESSION_SCRIPT 规则的 condition_ast 为 NULL(条件逻辑走 script_source),放开 condition_ast 的 NOT NULL 约束。
-- 与 V1_28(新增 script_source)配套:结构化 kind 走 condition_ast,脚本 kind 走 script_source,二者互斥可空。
ALTER TABLE rule_version
    MODIFY COLUMN condition_ast JSON NULL COMMENT '条件 AST;EXPRESSION_SCRIPT 规则为 NULL(脚本逻辑走 script_source)';
