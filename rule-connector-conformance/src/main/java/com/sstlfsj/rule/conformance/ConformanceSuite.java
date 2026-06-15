package com.sstlfsj.rule.conformance;

import com.sstlfsj.rule.kernel.api.model.MetricFetchError;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 连接器一致性套件：对一组黄金用例逐条起桩 + 按标准连接器语义实测一次 HTTP + 映射，
 * 断言映射值 / 归一错误码符合连接器行为契约。这是契约的可执行规约——
 * 通过实测 HTTP 行为而非直调 eval handler，因此接入方引依赖后即可独立 run。
 * <p>内含标准连接器 HTTP 语义的参照实现（与 DeclarativeHttpConnectorHandler 同口径）：</p>
 * <ul>
 *   <li>状态：2xx 才继续；401/403 → UNAUTHORIZED，其余非 2xx → UPSTREAM_ERROR。</li>
 *   <li>解析：响应体非合法 JSON → PARSE_ERROR。</li>
 *   <li>取值：valuePath 点号路径取标量，未命中 → PARSE_ERROR。</li>
 * </ul>
 */
public final class ConformanceSuite {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    /**
     * 跑黄金用例集：每条起桩 → 发请求 → 映射 → 断言契约。任一用例不符抛 AssertionError。
     *
     * @param upstream 嵌入式 mock 上游
     * @param cases    黄金用例集
     */
    public void run(EmbeddedUpstream upstream, List<GoldenCase> cases) {
        for (GoldenCase testCase : cases) {
            runOne(upstream, testCase);
        }
    }

    /** 跑单条用例：起桩、实测、断言，确保上游真被请求过。 */
    private void runOne(EmbeddedUpstream upstream, GoldenCase testCase) {
        upstream.reset();
        upstream.stub(testCase);

        Outcome outcome = fetch(upstream.baseUrl(), testCase);

        upstream.verifyRequested(testCase.stubPath());

        if (testCase.expectedErrorCode() == null) {
            assertThat(outcome.errorCode())
                    .as("用例[%s] 期望成功但归一为错误码 %s", testCase.name(), outcome.errorCode())
                    .isNull();
            assertThat(outcome.value())
                    .as("用例[%s] 映射值不符契约", testCase.name())
                    .isEqualTo(testCase.expectedValue());
        } else {
            assertThat(outcome.errorCode())
                    .as("用例[%s] 错误归一不符契约", testCase.name())
                    .isEqualTo(testCase.expectedErrorCode());
        }
    }

    /** 标准连接器 HTTP 语义参照实现：发 GET、判状态、解析、按 valuePath 取值。 */
    private Outcome fetch(String baseUrl, GoldenCase testCase) {
        HttpResponse<String> response;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + testCase.stubPath()))
                    .GET()
                    .build();
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            return Outcome.error(MetricFetchError.UPSTREAM_ERROR);
        }

        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            return Outcome.error(fromHttpStatus(status));
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(response.body());
        } catch (Exception e) {
            return Outcome.error(MetricFetchError.PARSE_ERROR);
        }

        Object value = extractJsonPath(root, testCase.valuePath());
        if (value == null) {
            return Outcome.error(MetricFetchError.PARSE_ERROR);
        }
        return Outcome.value(value);
    }

    /** HTTP 状态 → 细码（与 MetricFetchErrorMapper 同口径：401/403→UNAUTHORIZED，其余→UPSTREAM_ERROR）。 */
    private static MetricFetchError fromHttpStatus(int status) {
        if (status == 401 || status == 403) return MetricFetchError.UNAUTHORIZED;
        return MetricFetchError.UPSTREAM_ERROR;
    }

    /** 按点号 jsonPath 取标量值（与 handler extractJsonPath 同口径），未命中返回 null。 */
    private static Object extractJsonPath(JsonNode root, String jsonPath) {
        JsonNode cur = root;
        for (String seg : jsonPath.split("\\.")) {
            if (cur == null) return null;
            cur = cur.get(seg);
        }
        if (cur == null || cur.isNull() || cur.isMissingNode()) return null;
        if (cur.isIntegralNumber()) return cur.longValue();
        if (cur.isFloatingPointNumber()) return cur.doubleValue();
        if (cur.isBoolean()) return cur.booleanValue();
        return cur.asString();
    }

    /** 取数结果：成功带 value、失败带 errorCode（MetricFetchError 名），二者互斥。 */
    private record Outcome(Object value, String errorCode) {
        static Outcome value(Object value) {
            return new Outcome(value, null);
        }

        static Outcome error(MetricFetchError err) {
            return new Outcome(null, err.tag());
        }
    }
}
