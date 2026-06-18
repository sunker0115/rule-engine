package com.sstlfsj.rule.config.internal.service;

import com.sstlfsj.rule.config.api.spi.MetricResourceCatalog;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;
import java.util.function.Supplier;

/**
 * 连接器写服务装配：从可选 SPI {@link MetricResourceCatalog} 适配出 endpoint 名供给，
 * 注入 {@link ConnectorWriteServiceImpl}。纯 config 部署无 eval catalog 时供给返回 null（跳过 endpoint 校验，
 * 照 {@code PublishService} 对 catalog 的可选注入范式）。
 */
@Configuration
class ConnectorWriteConfig {

    /**
     * endpoint 名供给：catalog 存在则返回其已注册端点名，否则返回 null。
     *
     * @param catalogProvider 可选取数资源目录 SPI（eval 侧实现，config 单独部署时缺省）
     * @return endpoint 名供给
     */
    @Bean
    Supplier<Set<String>> connectorEndpointNames(ObjectProvider<MetricResourceCatalog> catalogProvider) {
        return () -> {
            MetricResourceCatalog catalog = catalogProvider.getIfAvailable();
            return catalog != null ? catalog.endpointNames() : null;
        };
    }
}
