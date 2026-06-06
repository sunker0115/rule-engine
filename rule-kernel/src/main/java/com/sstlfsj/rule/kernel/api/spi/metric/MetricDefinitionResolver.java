package com.sstlfsj.rule.kernel.api.spi.metric;

import com.sstlfsj.rule.kernel.api.model.MetricDescriptor;

/**
 * 运行期解析 metricCode 到 {@link MetricDescriptor} 的 SPI。
 * <p><b>数据源无关</b>：服务端实现读 metric_definition 表（rule-eval-svc），
 * 嵌入式 SDK 实现读下发缓存（见 specs/2026-06-06-sdk-fetch-design.md）；两者共用同一抽象，
 * 上层取数编排不感知数据源。</p>
 */
public interface MetricDefinitionResolver {

    /**
     * 解析指定租户下某 metric 指定版本的运行时定义。
     *
     * @param tenantId      租户 id
     * @param metricCode    指标编码
     * @param metricVersion 规则快照绑定的版本号
     * @return 定义快照；不存在时返回 null
     */
    MetricDescriptor resolve(String tenantId, String metricCode, int metricVersion);
}
