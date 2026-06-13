-- EXPRESSION_SCRIPT 规则的脚本载体(ScriptSource JSON: {source, lang})；其它 kind 为 NULL。
-- 与 condition_ast 互斥:脚本规则 condition_ast=NULL、script_source 非空。
ALTER TABLE rule_version
    ADD COLUMN script_source JSON NULL COMMENT 'EXPRESSION_SCRIPT 脚本载体 {source,lang}，其它 kind 为 NULL';
