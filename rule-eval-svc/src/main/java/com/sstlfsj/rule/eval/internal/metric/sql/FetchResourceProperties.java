package com.sstlfsj.rule.eval.internal.metric.sql;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 取数资源配置：命名 DataSource / HTTP 端点 / 全局超时。
 * 凭证从环境变量/secrets 注入（如 password: ${RISK_RO_PASSWORD}），不落配置表。
 */
@ConfigurationProperties(prefix = "rule.fetch")
public class FetchResourceProperties {

    /** 全局取数超时毫秒（默认 800）。 */
    private long timeoutMs = 800;
    /** 命名只读数据源列表。 */
    private List<DataSourceDef> datasources = new ArrayList<>();
    /** 命名 HTTP 端点列表（Phase 3 使用）。 */
    private List<EndpointDef> endpoints = new ArrayList<>();

    public long getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(long timeoutMs) { this.timeoutMs = timeoutMs; }
    public List<DataSourceDef> getDatasources() { return datasources; }
    public void setDatasources(List<DataSourceDef> datasources) { this.datasources = datasources; }
    public List<EndpointDef> getEndpoints() { return endpoints; }
    public void setEndpoints(List<EndpointDef> endpoints) { this.endpoints = endpoints; }

    /** 命名只读数据源定义。 */
    public static class DataSourceDef {
        private String name;
        private String url;
        private String username;
        private String password;
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }

    /** 命名 HTTP 端点定义（Phase 3）。 */
    public static class EndpointDef {
        private String name;
        private String baseUrl;
        private String authHeaderName;
        private String authHeaderValue;
        private int connectTimeoutMs = 1000;
        private int readTimeoutMs = 2000;
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getAuthHeaderName() { return authHeaderName; }
        public void setAuthHeaderName(String authHeaderName) { this.authHeaderName = authHeaderName; }
        public String getAuthHeaderValue() { return authHeaderValue; }
        public void setAuthHeaderValue(String authHeaderValue) { this.authHeaderValue = authHeaderValue; }
        public int getConnectTimeoutMs() { return connectTimeoutMs; }
        public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
        public int getReadTimeoutMs() { return readTimeoutMs; }
        public void setReadTimeoutMs(int readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }
    }
}
