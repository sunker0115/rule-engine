package com.sstlfsj.rule.samples.sdkpolling;

import com.sstlfsj.rule.kernel.api.model.EventSource;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.sdk.RuleEngineClient;
import com.sstlfsj.rule.samples.support.DemoConfig;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 接入姿势二:SDK 轮询嵌入。SDK 定期从服务端拉规则快照,本地进程内零网络评估。
 * <p>适合谁:Java 接入方,要低延迟本地评估、又想规则在服务端集中管理。
 * <p>运行前提:rule-app 已在 localhost:8080 启动,且租户 (9001,'samples') 已存在(见 README);
 * demo 启动时先 seed 配置(boilerplate,非看点)。
 * <p>怎么跑:{@code $MVN -pl rule-samples exec:java -Dexec.mainClass="com.sstlfsj.rule.samples.sdkpolling.SdkPollingDemo"}
 * <p>starter 等价写法见 README(application.yml 配 rule.sdk.serverUrl/tenantId/pollInterval)。
 */
public final class SdkPollingDemo {

    private SdkPollingDemo() {
    }

    public static void main(String[] args) throws InterruptedException {
        String baseUrl = "http://localhost:8080";
        DemoConfig.seed(baseUrl);

        try (RuleEngineClient client = RuleEngineClient.builder()
                .serverUrl(baseUrl)
                .tenantId(DemoConfig.TENANT_ID)
                .pollInterval(Duration.ofSeconds(2))
                .build()) {

            // 等首次轮询拉取完成
            Thread.sleep(4000);

            RuleEvent big = event(8000);
            System.out.println("[sdk-polling] amount=8000 ruleHit=" + client.evaluate(big).ruleHit());

            RuleEvent small = event(100);
            System.out.println("[sdk-polling] amount=100  ruleHit=" + client.evaluate(small).ruleHit());
        }
    }

    private static RuleEvent event(int amount) {
        return new RuleEvent(
                DemoConfig.TENANT_ID, DemoConfig.SCENE_CODE, "trade", "merchant-1",
                UUID.randomUUID().toString(), Instant.now(),
                Map.of("amount", amount), Map.of(), EventSource.SDK);
    }
}
