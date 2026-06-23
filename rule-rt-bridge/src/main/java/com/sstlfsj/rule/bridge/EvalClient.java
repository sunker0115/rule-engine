package com.sstlfsj.rule.bridge;

import com.sstlfsj.rule.bridge.model.SuspectPayload;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.Map;

/** 调 rule-api /api/v1/rule/evaluate（EvalEventRequest），解 ApiResponse&lt;EvalResult&gt; 取 finalDecision.code。 */
public class EvalClient {

    private final RestClient restClient;
    private final String tenantCode;
    private final String sceneCode;
    private final String eventType;

    public EvalClient(RestClient restClient, String tenantCode, String sceneCode, String eventType) {
        this.restClient = restClient;
        this.tenantCode = tenantCode;
        this.sceneCode = sceneCode;
        this.eventType = eventType;
    }

    /** @param p suspect 事件 @return 决策码（finalDecision.code），无决策时返回 null。 */
    @SuppressWarnings("unchecked")
    public String evaluate(SuspectPayload p) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("susScore", p.susScore());
        payload.put("rtState", p.rtState());
        payload.put("rtmMwr1s", p.rtmMwr1s());
        payload.put("rtmMwr10s", p.rtmMwr10s());
        payload.put("rtmMwr1m", p.rtmMwr1m());
        payload.put("rtmMwr5m", p.rtmMwr5m());
        payload.put("rtdAmountSum", p.rtdAmountSum());
        payload.put("fastTradeRatio", p.fastTradeRatio());

        Map<String, Object> req = new HashMap<>();
        req.put("tenantCode", tenantCode);
        req.put("sceneCode", sceneCode);
        req.put("eventType", eventType);
        req.put("subjectId", p.customerId());
        req.put("eventId", p.suspectId());
        req.put("occurredAt", p.occurredAt() == null ? null : p.occurredAt().toString());
        req.put("payload", payload);

        Map<String, Object> resp = restClient.post()
                .uri("/api/v1/rule/evaluate")
                .body(req)
                .retrieve()
                .body(Map.class);

        if (resp == null) return null;
        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        if (data == null) return null;
        Map<String, Object> finalDecision = (Map<String, Object>) data.get("finalDecision");
        return finalDecision == null ? null : (String) finalDecision.get("code");
    }
}
