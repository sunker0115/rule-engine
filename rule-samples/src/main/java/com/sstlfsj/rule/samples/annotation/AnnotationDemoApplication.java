package com.sstlfsj.rule.samples.annotation;

import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.EventSource;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.sdk.RuleEngineClient;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 接入姿势四:注解规则即代码(Spring Boot starter)。规则用 Java 类 + 注解声明,
 * starter 自动扫描 {@code @RuleDef} / {@code @ConditionType} Bean 装配 RuleEngineClient,
 * 完全不连服务端。
 * <p>适合谁:规则逻辑随应用代码演进、希望强类型 + IDE 重构友好的接入方。
 * <p>运行前提:无,直接跑。
 * <p>怎么跑:{@code $MVN -pl rule-samples exec:java -Dexec.mainClass="com.sstlfsj.rule.samples.annotation.AnnotationDemoApplication"}
 */
@SpringBootApplication
public class AnnotationDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(AnnotationDemoApplication.class, args);
    }

    /** 容器就绪后评估一个示例事件并打印(starter 已自动注入装配好的 RuleEngineClient)。 */
    @Bean
    CommandLineRunner demoRunner(RuleEngineClient client) {
        return args -> {
            RuleEvent event = new RuleEvent(
                    "9001", "merchant-trade", "trade", "merchant-1",
                    UUID.randomUUID().toString(), Instant.now(),
                    Map.of("amount", 8000, "hour", 10), Map.of(), EventSource.SDK);

            EvalResult result = client.evaluate(event);
            System.out.println("[annotation] amount=8000 hour=10 ruleHit=" + result.ruleHit()
                    + " finalDecision=" + (result.finalDecision() == null
                            ? null : result.finalDecision().code()));
        };
    }
}
