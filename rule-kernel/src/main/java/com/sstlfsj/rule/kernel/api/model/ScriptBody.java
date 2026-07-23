package com.sstlfsj.rule.kernel.api.model;

/**
 * EXPRESSION_SCRIPT 规则载体。
 *
 * @param script 脚本源码 + 引擎标识
 */
public record ScriptBody(ScriptSource script) implements RuleBody {
}
