package com.sstlfsj.rule.kernel.api.model;

/** 表达式引擎语言标识(== ScriptSource.lang / 路由到对应 ExpressionEngine 的 key)。开放可扩展(Aviator/Lua 为 opt-in 插件)。 */
public enum ExpressionLang {
    /** Google CEL:受限表达式语言,盒内默认引擎。 */
    CEL;

    /** 序列化/路由用的字符串标签(== 枚举名)。 */
    public String tag() {
        return name();
    }
}
