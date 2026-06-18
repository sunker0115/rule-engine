package com.sstlfsj.rule.kernel.api.analysis;

/** 被更高优先级/在先规则完全覆盖,永不胜出。 */
public record DeadRuleFinding(String deadRuleCode, String coveredByRuleCode, String reason, Severity severity) {
}
