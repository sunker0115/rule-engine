package com.sstlfsj.rule.config.api.service;

import com.sstlfsj.rule.kernel.api.analysis.RuleSetAnalysisReport;

/** 规则集静态分析:读取场景下每条规则的版本快照(草稿优先,否则 ACTIVE),运行 kernel 分析器返回报告。 */
public interface RuleAnalysisService {

    /**
     * 对指定场景执行静态分析。每条规则取 DRAFT 版本优先、否则 ACTIVE(反映待发布编辑态,支持发布前自查)。
     *
     * @param tenantId  租户 ID
     * @param sceneCode 场景编码
     * @return 聚合各类发现的分析报告;场景无可分析规则时返回各列表均为空的报告
     * @throws IllegalArgumentException 场景不存在时抛出
     */
    RuleSetAnalysisReport analyze(Long tenantId, String sceneCode);
}
