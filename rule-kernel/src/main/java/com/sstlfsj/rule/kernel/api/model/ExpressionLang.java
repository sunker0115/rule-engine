package com.sstlfsj.rule.kernel.api.model;

/** 表达式引擎语言标识(== ScriptSource.lang / 路由到对应 ExpressionEngine 的 key)。开放可扩展(Aviator/Lua 为 opt-in 插件)。 */
public enum ExpressionLang {
    /** Google CEL:受限表达式语言,盒内默认引擎。 */
    CEL,
    /** Aviator:高性能 JVM 动态表达式引擎,弱类型。 */
    AVIATOR,
    /** QLExpress:阿里开源规则引擎,弱类型/动态类型。 */
    QLEXPRESS,
    /** JsonLogic:JSON 规则引擎,纯数据驱动,无代码执行能力。 */
    JSONLOGIC,
    /** Apache Commons JEXL:弱类型表达式引擎,经 JexlPermissions.RESTRICTED 沙箱化。 */
    JEXL,
    /** Apache Groovy:完整 JVM 脚本语言,经 groovy-sandbox 运行期拦截 + deny-by-default 白名单沙箱化。 */
    GROOVY;

    /** 序列化/路由用的字符串标签(== 枚举名)。 */
    public String tag() {
        return name();
    }
}
