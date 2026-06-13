package com.sstlfsj.rule.kernel.api.model;

/**
 * EXPRESSION_SCRIPT 规则的脚本载体;与 AST 平级、不实现 AstNode。
 * source 为表达式源码,lang 标识引擎(默认 CEL)。
 */
public record ScriptSource(String source, String lang) {
    public ScriptSource {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("script source 不能为空");
        }
        lang = (lang == null || lang.isBlank()) ? ExpressionLang.CEL.tag() : lang;
    }
}
