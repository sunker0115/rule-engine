package com.sstlfsj.rule.samples.httpclient;

import com.sstlfsj.rule.samples.support.DemoConfig;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 接入姿势一:HTTP 远程。引擎是独立服务,接入方只发 REST。
 * <p>适合谁:不想嵌入 SDK、跨语言、希望规则集中在服务端的接入方。
 * <p>运行前提:rule-app 已在 localhost:8080 启动,且租户 (9001,'samples') 已存在(见 README)。
 * <p>怎么跑:{@code $MVN -pl rule-samples exec:java -Dexec.mainClass="com.sstlfsj.rule.samples.httpclient.HttpClientDemo"}
 */
public final class HttpClientDemo {

    private HttpClientDemo() {
    }

    public static void main(String[] args) {
        String baseUrl = "http://localhost:8080";

        // 1) 用 admin API 把场景/决策/规则建好并发布
        DemoConfig.seed(baseUrl);

        // 2) 公开评估接口:边界用 tenantCode,payload 携带 amount
        RestClient http = RestClient.create();
        Map<String, Object> evalBody = Map.of(
                "tenantCode", DemoConfig.TENANT_CODE,
                "sceneCode", DemoConfig.SCENE_CODE,
                "eventType", "trade",
                "subjectId", "merchant-1",
                "eventId", UUID.randomUUID().toString(),
                "occurredAt", Instant.now().toString(),
                "payload", Map.of("amount", 8000));

        Map<?, ?> resp = http.post()
                .uri(baseUrl + "/api/v1/rule/evaluate")
                .contentType(MediaType.APPLICATION_JSON)
                .body(evalBody)
                .retrieve()
                .toEntity(Map.class)
                .getBody();

        System.out.println("[http-client] /api/v1/rule/evaluate response = " + resp);
    }
}
