package com.sstlfsj.rule.samples.metric;

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
 * Metric 特性 demo:演示 {@code @Metric} 注入派生指标驱动决策(规则放本地注解,取数走 stub handler,零服务依赖)。
 * {@link VelocityRule} 依赖 recent_txn_count(由 {@link RecentTxnCountHandler} 模拟取数):同样大额交易,
 * frequent-user 近期交易数 5 → 命中复核,normal-user 为 1 → 不命中,证明决策由预拉 metric 驱动。
 * <p>怎么跑:{@code $MVN -pl rule-samples exec:java -Dexec.mainClass="com.sstlfsj.rule.samples.metric.MetricDemoApplication"}
 */
@Slf4j
@SpringBootApplication
public class MetricDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(MetricDemoApplication.class, args);
    }

    /** 容器就绪后对两个主体各发一笔大额交易,打印 metric 预拉后是否命中。 */
    @Bean
    CommandLineRunner metricDemoRunner(RuleEngineClient client) {
        return args -> {
            EvalResult frequent = client.evaluate(txn("frequent-user", 2000));
            log.info("[metric] frequent-user amount=2000 recent_txn_count=5 → ruleHit={}", frequent.ruleHit());

            EvalResult normal = client.evaluate(txn("normal-user", 2000));
            log.info("[metric] normal-user   amount=2000 recent_txn_count=1 → ruleHit={}", normal.ruleHit());
        };
    }

    private static RuleEvent txn(String subjectId, int amount) {
        return RuleEvent.builder().tenantId("9001").sceneCode("velocity-demo").eventType("txn")
                .subjectId(subjectId).eventId(UUID.randomUUID().toString())
                .occurredAt(Instant.now()).payload(Map.of("amount", amount))
                .source(EventSource.SDK).build();
    }
}
