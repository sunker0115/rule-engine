package com.sstlfsj.rule.samples.support;

import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * 通过 admin HTTP API 把示例场景 merchant-trade 的 scene/decision/rule 建好并发布,
 * 供 httpclient / sdkpolling demo 在评估前 seed 配置。
 * 前提:rule-app 已启动,且对应租户(默认 id=9100, code='samples',见 {@link #TENANT_ID})已存在。
 */
public final class DemoConfig {

    /**
     * 示例租户内部 id(admin 写接口用)。需与你 seed 的 tenant 行 id 一致。
     * 默认取高位 9100 避开低段真实租户;覆盖优先级:系统属性 {@code -Ddemo.tenantId}
     * > 环境变量 {@code DEMO_TENANT_ID} > 默认 9100。被占用时 seed 另一个 id 并用 {@code -Ddemo.tenantId} 指向它。
     */
    public static final String TENANT_ID = resolve("demo.tenantId", "DEMO_TENANT_ID", "9100");
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
     * <p><b>幂等</b>:scene / decision / rule 三者均"已存在则跳过创建"(scene、decision 无删除 API;
     * rule 一旦发布即复用,内容固定无需重建)。因此本方法可重复调用,demo 反复跑不会因"资源已存在"报错。
     * 残留的 evaluation_session / audit_log 是 D14 不可变审计,不影响重跑;需彻底清空见模块根 cleanup.sql。
     *
     * @param baseUrl rule-app 根地址,如 http://localhost:8080
     */
    public static void seed(String baseUrl) {
        String admin = baseUrl + "/admin/v1";

        if (!sceneExists(admin)) {
            post(admin + "/scenes", Map.of(
                    "tenantId", TENANT_ID,
                    "sceneCode", SCENE_CODE,
                    "name", "商户交易风控",
                    "dominantMode", "PULL",
                    "subjectType", "USER",
                    "eventTypes", List.of("trade"),
                    "payloadSchema", List.of(Map.of("name", "amount", "type", "NUMBER", "required", true)),
                    "defaultParams", Map.of()));
        }

        if (!decisionExists(admin, "REVIEW")) {
            post(admin + "/decisions?tenantId=" + TENANT_ID, Map.of(
                    "code", "REVIEW",
                    "name", "人工审核",
                    "priority", 50,
                    "description", "samples",
                    "actions", List.of()));
        }

        // rule 已存在(首次已建并发布)则直接复用;不存在才创建并发布
        if (findRuleId(admin, "large-trade") != null) {
            return;
        }

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

    /** scene 列表里是否已有本示例场景(列表接口返回 200,无需处理 404;SceneListItem 的编码字段名为 sceneCode)。 */
    private static boolean sceneExists(String admin) {
        return listHasField(get(admin + "/scenes?tenantId=" + TENANT_ID), "sceneCode", SCENE_CODE);
    }

    /** decision 列表里是否已有指定 code(DecisionDefinition 的编码字段名为 code)。 */
    private static boolean decisionExists(String admin, String code) {
        return listHasField(get(admin + "/decisions?tenantId=" + TENANT_ID), "code", code);
    }

    /** 在规则列表里按 code 找规则定义 id,找不到返回 null。 */
    private static Long findRuleId(String admin, String code) {
        Map<?, ?> body = get(admin + "/rules?tenantId=" + TENANT_ID + "&sceneCode=" + SCENE_CODE);
        Object data = body == null ? null : body.get("data");
        if (!(data instanceof Map<?, ?> page)) {
            return null;
        }
        if (!(page.get("items") instanceof List<?> items)) {
            return null;
        }
        for (Object row : items) {
            if (row instanceof Map<?, ?> m && code.equals(m.get("code"))) {
                return ((Number) m.get("ruleDefinitionId")).longValue();
            }
        }
        return null;
    }

    /** ApiResponse.data 为 List 时,判断其中是否存在指定字段等于目标值的元素。 */
    private static boolean listHasField(Map<?, ?> body, String field, String value) {
        Object data = body == null ? null : body.get("data");
        if (!(data instanceof List<?> list)) {
            return false;
        }
        return list.stream().anyMatch(e -> e instanceof Map<?, ?> m && value.equals(m.get(field)));
    }

    private static Map<?, ?> get(String url) {
        return ADMIN.get()
                .uri(url)
                .header("X-Actor-Id", "samples")
                .retrieve()
                .toEntity(Map.class)
                .getBody();
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
