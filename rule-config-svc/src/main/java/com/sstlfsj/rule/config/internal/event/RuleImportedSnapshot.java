package com.sstlfsj.rule.config.internal.event;

/**
 * 规则导入快照：IMPORT 时记录导入产生的规则版本 id、版本号及规则是否已存在。
 *
 * @param ruleVersionId 导入产生的规则版本 id
 * @param version       版本号
 * @param ruleExisted   导入前规则定义是否已存在（true=新增版本，false=同时新建定义）
 */
public record RuleImportedSnapshot(Long ruleVersionId, long version, boolean ruleExisted) implements AuditSnapshot {
}
