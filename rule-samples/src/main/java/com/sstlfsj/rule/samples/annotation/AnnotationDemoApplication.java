package com.sstlfsj.rule.samples.annotation;

import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.EventSource;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.sdk.RuleEngineClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 接入姿势四:注解规则即代码(Easy Rules 风格,Spring Boot starter)。本包下的规则类都用注解声明,
 * starter 自动扫描装配、把命中决策派发回各自的处理器,完全不连服务端。本 runner 逐一评估,演示四种判定原语:
 * <ul>
 *   <li>{@link LargeTradeRule} — {@code @Condition} 布尔条件 + 动作(甲 {@code @EventListener}/乙 {@code @OnDecision});</li>
 *   <li>{@link NestedOrderRule} — {@code @Condition} + {@code @Fact("order.amount")} 嵌套路径注入;</li>
 *   <li>{@link CreditScoreRule} — {@code @Score} + {@code @ScoreBand} 评分卡分档;</li>
 *   <li>{@link RiskDecideRule} — {@code @Decide} Java 多分支直接产出决策码。</li>
 * </ul>
 * <p>运行前提:无,直接跑。
 * <p>怎么跑:{@code $MVN -pl rule-samples exec:java -Dexec.mainClass="com.sstlfsj.rule.samples.annotation.AnnotationDemoApplication"}
 */
@Slf4j
@SpringBootApplication
public class AnnotationDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(AnnotationDemoApplication.class, args);
    }

    /** 容器就绪后,对四条注解规则各发一个事件并打印决策;LargeTradeRule 命中时甲/乙处理器另有日志输出。 */
    @Bean
    CommandLineRunner demoRunner(RuleEngineClient client) {
        return args -> {
            // 1) @Condition + 动作:大额交易且营业时段 → REVIEW(甲/乙处理器各触发,见其日志)
            EvalResult trade = client.evaluate(
                    event("merchant-trade", "trade", Map.of("amount", 8000, "hour", 10)));
            log.info("[annotation][@Condition] 大额交易 amount=8000 hour=10 → {}", code(trade));

            // 2) @Condition 嵌套路径:payload.order.amount 经 @Fact("order.amount") 注入
            EvalResult order = client.evaluate(
                    event("order-demo", "order", Map.of("order", Map.of("amount", 20000))));
            log.info("[annotation][嵌套路径] order.amount=20000 → {}", code(order));

            // 3) @Score 评分卡:信用分 72 落在 [60,80) → MANUAL_REVIEW,并带回分值
            EvalResult credit = client.evaluate(
                    event("credit-demo", "apply", Map.of("score", 72)));
            log.info("[annotation][@Score] 信用分 72 → {} score={}", code(credit), credit.score());

            // 4) @Decide 多分支风控:金额 99999 → BLOCK
            EvalResult risk = client.evaluate(
                    event("risk-demo", "txn", Map.of("amount", 99999)));
            log.info("[annotation][@Decide] 风控 amount=99999 → {}", code(risk));
        };
    }

    private static RuleEvent event(String scene, String type, Map<String, Object> payload) {
        return RuleEvent.builder()
                .tenantId("9001").sceneCode(scene).eventType(type)
                .subjectId("subject-1").eventId(UUID.randomUUID().toString())
                .occurredAt(Instant.now())
                .payload(payload)
                .source(EventSource.SDK).build();
    }

    private static String code(EvalResult r) {
        return r.finalDecision() == null ? null : r.finalDecision().code();
    }
}
