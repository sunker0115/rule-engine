package com.sstlfsj.rule.config.api.service;

import com.sstlfsj.rule.kernel.api.analysis.RuleSetAnalysisReport;

/** 规则集静态分析:读取场景下全部 ACTIVE 规则快照,运行 kernel 分析器返回报告。 */
public interface RuleAnalysisService {

    /**
     * 对指定场景的当前 ACTIVE 规则集执行静态分析。
     *
     * @param tenantId  租户 ID
     * @param sceneCode 场景编码
     * @return 聚合各类发现的分析报告;场景无 ACTIVE 规则时返回各列表均为空的报告
     * @throws IllegalArgumentException 场景不存在时抛出
     */
    RuleSetAnalysisReport analyze(Long tenantId, String sceneCode);
}
