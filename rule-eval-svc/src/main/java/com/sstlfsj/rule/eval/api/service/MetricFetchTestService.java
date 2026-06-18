package com.sstlfsj.rule.eval.api.service;

import com.sstlfsj.rule.eval.api.FetchTrace;

import java.util.Map;

/** metric 取数自助测试服务（跨源，供 admin :test 端点）。 */
public interface MetricFetchTestService {

    /**
     * 用样例输入实打实取数一次，返回分阶段 trace。
     *
     * @param tenantId        租户 id
     * @param metricCode      被测 metric
     * @param sampleVars      样例 vars（异构）
     * @param samplePayload   样例 payload（异构）
     * @param sampleSubjectId 样例主体 id
     * @return 分阶段 trace
     */
    FetchTrace test(Long tenantId, String metricCode,
                    Map<String, Object> sampleVars, Map<String, Object> samplePayload, String sampleSubjectId);

    /**
     * 直测某连接器（不经 metric，传临时 vars），返回分阶段 trace。供 connector :test 端点（用户选 A）。
     *
     * @param tenantId        租户 id
     * @param connectorCode   被测连接器
     * @param sampleVars      样例 vars（异构）
     * @param samplePayload   样例 payload（异构）
     * @param sampleSubjectId 样例主体 id
     * @return 分阶段 trace
     */
    FetchTrace testConnector(Long tenantId, String connectorCode,
                             Map<String, Object> sampleVars, Map<String, Object> samplePayload, String sampleSubjectId);
}
