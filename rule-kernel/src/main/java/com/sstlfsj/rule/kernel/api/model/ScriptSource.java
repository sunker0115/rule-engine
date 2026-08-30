package com.sstlfsj.rule.kernel.api.model;

import java.util.Map;

/**
 * EXPRESSION_SCRIPT 规则的脚本载体;与 AST 平级、不实现 AstNode。
 * source 为表达式源码,lang 标识引擎(默认 CEL),params 为冻结常量命名空间(求值期并入 binding 的顶层 {@code params} key)。
 */
public record ScriptSource(String source, String lang, Map<String, Object> params) {

    public ScriptSource {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("script source 不能为空");
        }
        lang = (lang == null || lang.isBlank()) ? ExpressionLang.CEL.tag() : lang;
        params = params == null ? Map.of() : Map.copyOf(params);
    }

    /** 向后兼容:无冻结常量的脚本(默认空 params)。 */
    public ScriptSource(String source, String lang) {
        this(source, lang, Map.of());
    }
}
