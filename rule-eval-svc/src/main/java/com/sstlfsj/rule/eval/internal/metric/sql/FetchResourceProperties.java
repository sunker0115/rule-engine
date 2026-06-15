package com.sstlfsj.rule.eval.internal.metric.sql;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 取数资源配置：命名 DataSource / HTTP 端点 / 全局超时。
 * 凭证从环境变量/secrets 注入（如 password: ${RISK_RO_PASSWORD}），不落配置表。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "engine.rule.fetch")
public class FetchResourceProperties {

    /** 全局取数超时毫秒（默认 800）。 */
    private long timeoutMs = 800;
    /** 命名只读数据源列表。 */
    private List<DataSourceDef> datasources = new ArrayList<>();
    /** 命名 HTTP 端点列表（Phase 3 使用）。 */
    private List<EndpointDef> endpoints = new ArrayList<>();
    /** 命名凭证列表：连接器 auth 的 *Ref 按 name 解析为 value，值来自 env/secrets，不落 metric/connector。 */
    private List<CredentialDef> credentials = new ArrayList<>();

    /** 命名只读数据源定义。 */
    @Getter
    @Setter
    public static class DataSourceDef {
        private String name;
        private String url;
        private String username;
        private String password;
    }

    /** 命名 HTTP 端点定义（Phase 3）。 */
    @Getter
    @Setter
    public static class EndpointDef {
        private String name;
        private String baseUrl;
        private String authHeaderName;
        private String authHeaderValue;
        private int connectTimeoutMs = 1000;
        private int readTimeoutMs = 2000;
    }

    /** 命名凭证定义：name 为连接器 auth *Ref 引用名，value 为实际密钥（来自 env/secrets）。 */
    @Getter
    @Setter
    public static class CredentialDef {
        private String name;
        private String value;
    }
}
