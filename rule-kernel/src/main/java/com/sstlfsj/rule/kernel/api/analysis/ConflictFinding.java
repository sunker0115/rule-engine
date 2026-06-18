package com.sstlfsj.rule.kernel.api.analysis;

/**
 * 两处输入相交但产出对立决策。
 * loc 是位置标识:规则用 ruleCode,决策表行用 "&lt;tableCode&gt;#row{n}" 字符串。
 */
public record ConflictFinding(String locA, String locB, String decisionA, String decisionB,
                              String reason, Severity severity) {
}
