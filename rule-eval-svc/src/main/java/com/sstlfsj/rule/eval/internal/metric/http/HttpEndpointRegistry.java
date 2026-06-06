package com.sstlfsj.rule.eval.internal.metric.http;

import com.sstlfsj.rule.eval.internal.metric.sql.FetchResourceProperties;
import org.springframework.stereotype.Component;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 命名 HTTP 端点注册表：按配置建 HttpClient 与端点元数据（baseUrl/鉴权头/超时）。
 * metric 只能引用已注册的端点名，杜绝自由 URL 与 SSRF；凭证来自配置（env/secrets），不进 metric。
 */
@Component
public class HttpEndpointRegistry {

    /** 端点运行时句柄。 */
    public record Endpoint(String baseUrl, String authHeaderName, String authHeaderValue,
                           int readTimeoutMs, HttpClient client) {}

    private final Map<String, Endpoint> endpoints = new HashMap<>();

    public HttpEndpointRegistry(FetchResourceProperties props) {
        for (FetchResourceProperties.EndpointDef def : props.getEndpoints()) {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(def.getConnectTimeoutMs()))
                    .build();
            endpoints.put(def.getName(), new Endpoint(
                    def.getBaseUrl(), def.getAuthHeaderName(), def.getAuthHeaderValue(),
                    def.getReadTimeoutMs(), client));
        }
    }

    /**
     * 取命名端点句柄。
     *
     * @param name 端点逻辑名
     * @return 句柄；未注册返回 null
     */
    public Endpoint get(String name) {
        return endpoints.get(name);
    }

    /** @return 所有已注册端点名（供发布期资源名校验）。 */
    public Set<String> names() {
        return Set.copyOf(endpoints.keySet());
    }
}
