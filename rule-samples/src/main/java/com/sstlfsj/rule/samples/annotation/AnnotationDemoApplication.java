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
 * 接入姿势四:注解规则即代码(Easy Rules 风格,Spring Boot starter)。规则({@link LargeTradeRule})
 * 与动作处理器({@link ReviewHandlers})都用 Java 类 + 注解声明,starter 自动扫描 {@code @RuleDef}+
 * {@code @Condition} 装配规则、把命中决策派发给 {@code @EventListener} / {@code @OnDecision},完全不连服务端。
 * <p>适合谁:规则随应用代码演进、要强类型 + IDE 重构友好的接入方。
 * <p>运行前提:无,直接跑。
 * <p>怎么跑:{@code $MVN -pl rule-samples exec:java -Dexec.mainClass="com.sstlfsj.rule.samples.annotation.AnnotationDemoApplication"}
 */
@SpringBootApplication
public class AnnotationDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(AnnotationDemoApplication.class, args);
    }

    /** 容器就绪后评估命中 / 不命中各一例;命中时甲、乙两种动作处理器各被触发一次(见控制台输出)。 */
    @Bean
    CommandLineRunner demoRunner(RuleEngineClient client) {
        return args -> {
            // 大额 + 营业时段 → 命中 REVIEW,甲 @EventListener / 乙 @OnDecision 各触发
            EvalResult hit = client.evaluate(trade(8000, 10));
            System.out.println("[annotation] amount=8000 hour=10 ruleHit=" + hit.ruleHit()
                    + " finalDecision=" + code(hit));

            // 大额但非营业时段 → 不命中,动作不触发
            EvalResult miss = client.evaluate(trade(8000, 3));
            System.out.println("[annotation] amount=8000 hour=3  ruleHit=" + miss.ruleHit()
                    + " finalDecision=" + code(miss));
        };
    }

    private static RuleEvent trade(int amount, int hour) {
        return RuleEvent.builder()
                .tenantId("9001").sceneCode("merchant-trade").eventType("trade")
                .subjectId("merchant-1").eventId(UUID.randomUUID().toString())
                .occurredAt(Instant.now())
                .payload(Map.of("amount", amount, "hour", hour))
                .source(EventSource.SDK).build();
    }

    private static String code(EvalResult r) {
        return r.finalDecision() == null ? null : r.finalDecision().code();
    }
}
