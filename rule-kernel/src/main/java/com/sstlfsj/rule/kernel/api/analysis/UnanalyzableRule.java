package com.sstlfsj.rule.kernel.api.analysis;

/** 超出 v1 精确推理(含 OR/正则/脚本等),跳过——非"无问题"。 */
public record UnanalyzableRule(String ruleCode, String reason) {
}
