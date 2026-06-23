package com.sstlfsj.rule.bridge;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

/** rt-bridge：消费 suspect → HTTP 调引擎 → 发 decision。 */
@SpringBootApplication
public class RtBridgeApp {

    public static void main(String[] args) {
        SpringApplication.run(RtBridgeApp.class, args);
    }

    /** EvalClient Bean：注入 rule-api 基址 + 评估事件维度配置。 */
    @Bean
    public EvalClient evalClient(
            @Value("${bridge.rule-api-base-url:http://localhost:8080}") String baseUrl,
            @Value("${bridge.tenant-code}") String tenantCode,
            @Value("${bridge.scene-code}") String sceneCode,
            @Value("${bridge.event-type:trade.suspect}") String eventType) {
        RestClient restClient = RestClient.builder().baseUrl(baseUrl).build();
        return new EvalClient(restClient, tenantCode, sceneCode, eventType);
    }
}
