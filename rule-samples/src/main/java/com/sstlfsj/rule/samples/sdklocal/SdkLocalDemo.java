package com.sstlfsj.rule.samples.sdklocal;

import com.sstlfsj.rule.kernel.api.model.EventSource;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.sdk.RuleEngineClient;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 接入姿势三:SDK 本地 JSON 规则源。规则放本地文件,完全不连服务端,纯离线嵌入。
 * <p>适合谁:离线/边缘场景,或规则随应用一起发布、不需要服务端集中管理的接入方。
 * <p>运行前提:无,直接跑。
 * <p>怎么跑:{@code $MVN -pl rule-samples exec:java -Dexec.mainClass="com.sstlfsj.rule.samples.sdklocal.SdkLocalDemo"}
 */
public final class SdkLocalDemo {

    private SdkLocalDemo() {
    }

    public static void main(String[] args) {
        try (RuleEngineClient client = RuleEngineClient.builder()
                .ruleFile("rules/large-trade.json")
                .build()) {

            RuleEvent event = new RuleEvent(
                    "9001", "merchant-trade", "trade", "merchant-1",
                    UUID.randomUUID().toString(), Instant.now(),
                    Map.of("amount", 8000), Map.of(), EventSource.SDK);

            EvalResult result = client.evaluate(event);
            System.out.println("[sdk-local] amount=8000 ruleHit=" + result.ruleHit());
        }
    }
}
