package com.sstlfsj.rule.eval.api.service;

import java.time.Instant;

/**
 * 回灌标签来源 SPI：按 watermark 增量批量拉标签。每种源一个实现，Spring 自动收集，按 configType 路由。
 *
 * @param <C> 该源的 OutcomeSourceConfig 子类型
 */
public interface OutcomeSource<C extends OutcomeSourceConfig> {

    /** 处理的源配置子类型。 */
    Class<C> configType();

    /**
     * 拉 watermark 之后的标签行。
     *
     * @param source    源配置
     * @param watermark 上次水位（null=首次全量）；仅返回 labeledAt &gt; watermark 的行
     * @param tenantId  租户 id
     * @return 标签行 + 新水位
     */
    OutcomePullResult pull(C source, Instant watermark, Long tenantId);
}
