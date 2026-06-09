package com.sstlfsj.rule.eval.internal.metric.http;

import com.sstlfsj.rule.eval.internal.metric.DataTypeCoercion;
import com.sstlfsj.rule.kernel.api.annotation.MetricSourceType;
import com.sstlfsj.rule.kernel.api.model.EvalErrorCode;
import com.sstlfsj.rule.kernel.api.model.MetricQuery;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.SourceType;
import com.sstlfsj.rule.kernel.api.model.ValueSource;
import com.sstlfsj.rule.kernel.api.spi.metric.MetricSourceHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * EXTERNAL_HTTP 取数 handler：引用命名端点 + 相对 path（占位符）+ jsonPath。
 * 200+jsonPath 命中→FETCHED；200 无匹配→null；非 200/超时/连接失败→METRIC_FETCH_FAIL。
 */
@Component
@MetricSourceType(SourceType.EXTERNAL_HTTP)
public class ExternalHttpMetricSourceHandler implements MetricSourceHandler {

    private static final Pattern PH = Pattern.compile("\\{([a-zA-Z_][\\w.]*)\\}");

    private final HttpEndpointRegistry registry;
    private final ObjectMapper objectMapper;

    public ExternalHttpMetricSourceHandler(HttpEndpointRegistry registry, ObjectMapper objectMapper) {
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    @Override
    public MetricValue fetch(MetricQuery query) {
        Map<String, Object> p = query.params();
        Object endpointName = p.get("endpoint");
        Object path = p.get("path");
        Object jsonPath = p.get("jsonPath");
        Object dataType = p.get("dataType");
        if (endpointName == null || path == null || jsonPath == null) return MetricValue.error(EvalErrorCode.METRIC_FETCH_FAIL);
        HttpEndpointRegistry.Endpoint ep = registry.get(endpointName.toString());
        if (ep == null) return MetricValue.error(EvalErrorCode.METRIC_FETCH_FAIL);
        try {
            String rendered = renderPath(path.toString(), query.eventPayload(), castParams(p.get("params")));
            HttpRequest.Builder req = HttpRequest.newBuilder()
                    .uri(URI.create(ep.baseUrl() + rendered))
                    .timeout(Duration.ofMillis(ep.readTimeoutMs()))
                    .GET();
            if (ep.authHeaderName() != null && !ep.authHeaderName().isBlank()) {
                req.header(ep.authHeaderName(), ep.authHeaderValue());
            }
            HttpResponse<String> resp = ep.client().send(req.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return MetricValue.error(EvalErrorCode.METRIC_FETCH_FAIL);
            JsonNode root = objectMapper.readTree(resp.body());
            Object raw = extractJsonPath(root, jsonPath.toString());
            String dt = dataType != null ? dataType.toString() : null;
            return new MetricValue(DataTypeCoercion.coerce(raw, dt), dt, ValueSource.FETCHED.tag());
        } catch (Exception e) {
            return MetricValue.error(EvalErrorCode.METRIC_FETCH_FAIL);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castParams(Object raw) {
        return raw instanceof Map ? (Map<String, Object>) raw : Map.of();
    }

    /**
     * 渲染 path 占位符 {payload.x}/{params.x}，替换后对各段做 URL 编码。
     *
     * @param path    含占位符的相对路径
     * @param payload 事件 payload
     * @param params  metric.params.params 子 map
     * @return 渲染并编码后的路径
     */
    public static String renderPath(String path, Map<String, Object> payload, Map<String, Object> params) {
        Matcher m = PH.matcher(path);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String token = m.group(1);
            String[] parts = token.split("\\.", 2);
            Object value = parts.length == 2 ? switch (parts[0]) {
                case "payload" -> payload.get(parts[1]);
                case "params" -> params.get(parts[1]);
                default -> null;
            } : null;
            String enc = URLEncoder.encode(String.valueOf(value), StandardCharsets.UTF_8).replace("+", "%20");
            m.appendReplacement(out, Matcher.quoteReplacement(enc));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * 按点号 jsonPath 从 JSON 树取值（如 "data.balance"）。
     *
     * @param root     JSON 根
     * @param jsonPath 点号路径
     * @return 命中的标量值（Number/String/Boolean）；未命中返回 null
     */
    public static Object extractJsonPath(JsonNode root, String jsonPath) {
        JsonNode cur = root;
        for (String seg : jsonPath.split("\\.")) {
            if (cur == null) return null;
            cur = cur.get(seg);
        }
        if (cur == null || cur.isNull() || cur.isMissingNode()) return null;
        if (cur.isIntegralNumber()) return cur.intValue();
        if (cur.isFloatingPointNumber()) return cur.doubleValue();
        if (cur.isBoolean()) return cur.booleanValue();
        return cur.asString();
    }
}
