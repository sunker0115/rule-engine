package com.sstlfsj.rule.samples.support;

import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * 通过 admin HTTP API 把示例场景 merchant-trade 的 scene/decision/rule 建好并发布,
 * 供 httpclient / sdkpolling demo 在评估前 seed 配置。
 * 前提:rule-app 已启动,且租户 (id=9001, code='samples') 已存在。
 */
public final class DemoConfig {

    /**
     * 示例租户内部 id(admin 写接口用)。需与你 seed 的 tenant 行 id 一致。
     * 覆盖优先级:系统属性 {@code -Ddemo.tenantId} > 环境变量 {@code DEMO_TENANT_ID} > 默认 9001。
     * 当目标库里 9001 已被占用时,seed 另一个 id 的租户并用 {@code -Ddemo.tenantId} 指向它。
     */
    public static final String TENANT_ID = resolve("demo.tenantId", "DEMO_TENANT_ID", "9001");
    /**
     * 示例租户 code(公开评估接口用)。
     * 覆盖优先级:{@code -Ddemo.tenantCode} > {@code DEMO_TENANT_CODE} > 默认 samples。
     */
    public static final String TENANT_CODE = resolve("demo.tenantCode", "DEMO_TENANT_CODE", "samples");
    /** 示例场景编码。 */
    public static final String SCENE_CODE = "merchant-trade";

    private static String resolve(String prop, String env, String def) {
        String v = System.getProperty(prop);
        if (v == null || v.isBlank()) {
            v = System.getenv(env);
        }
        return (v == null || v.isBlank()) ? def : v;
    }

    private static final RestClient ADMIN = RestClient.create();

    private DemoConfig() {
    }

    /**
     * 建配并发布示例规则:scene → decision → rule → publish。
     * 注意:幂等性未处理,重复 seed 会因资源已存在而报错——demo 假设干净库或单次运行。
     *
     * @param baseUrl rule-app 根地址,如 http://localhost:8080
     */
    public static void seed(String baseUrl) {
        String admin = baseUrl + "/admin/v1";

        post(admin + "/scenes", Map.of(
                "tenantId", TENANT_ID,
                "sceneCode", SCENE_CODE,
                "name", "商户交易风控",
                "dominantMode", "PULL",
                "subjectType", "USER",
                "eventTypes", List.of("trade"),
                "payloadSchema", List.of(Map.of("name", "amount", "type", "NUMBER", "required", true)),
                "defaultParams", Map.of()));

        post(admin + "/decisions?tenantId=" + TENANT_ID, Map.of(
                "code", "REVIEW",
                "name", "人工审核",
                "priority", 50,
                "description", "samples",
                "actions", List.of()));

        Map<?, ?> ruleResp = post(admin + "/rules", Map.of(
                "tenantId", TENANT_ID,
                "sceneCode", SCENE_CODE,
                "code", "large-trade",
                "name", "大额交易",
                "conditionAst", Map.of(
                        "type", "ConditionNode",
                        "conditionType", "GT",
                        "metricCode", "amount",
                        "params", Map.of("threshold", 5000),
                        "valueRef", "PAYLOAD"),
                "decisionBindings", List.of(Map.of("decisionCode", "REVIEW")),
                "preGates", List.of(),
                "triggerEventTypes", List.of("trade"),
                "kind", "AST_BOOLEAN"));

        Map<?, ?> data = (Map<?, ?>) ruleResp.get("data");
        long ruleId = ((Number) data.get("ruleDefinitionId")).longValue();

        post(admin + "/rules/" + ruleId + "/publish?tenantId=" + TENANT_ID, null);
    }

    private static Map<?, ?> post(String url, Object body) {
        RestClient.RequestBodySpec spec = ADMIN.post()
                .uri(url)
                .header("X-Actor-Id", "samples")
                .contentType(MediaType.APPLICATION_JSON);
        return (body != null ? spec.body(body) : spec)
                .retrieve()
                .toEntity(Map.class)
                .getBody();
    }
}
