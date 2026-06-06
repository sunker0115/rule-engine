package com.sstlfsj.rule.config.api.spi;

import java.util.Set;

/**
 * 已注册取数资源名目录：供发布期校验 metric 引用的 datasource/endpoint 是否注册。
 * 由 rule-eval-svc（持有实际 registry）实现并以 Bean 暴露；config-svc 运行期可选注入。
 */
public interface MetricResourceCatalog {

    /** @return 已注册的命名数据源名集合。 */
    Set<String> datasourceNames();

    /** @return 已注册的命名 HTTP 端点名集合。 */
    Set<String> endpointNames();
}
