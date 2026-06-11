package com.sstlfsj.rule.example.scenario;

import com.sstlfsj.rule.example.ScenarioSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 订单风控：当日订单总金额超阈值 → 拦截。SQL_AGGREGATE 聚合取数。 */
class OrderFraudScenario extends ScenarioSupport {

    @BeforeEach
    void seedOrders() {
        jdbc.update("INSERT INTO orders (user_id, amount) VALUES (?, ?)", "u1", 8000.00);
        jdbc.update("INSERT INTO orders (user_id, amount) VALUES (?, ?)", "u1", 7000.00);
        jdbc.update("INSERT INTO orders (user_id, amount) VALUES (?, ?)", "u2", 500.00);
    }

    @Test
    void dailyOrderAmount_exceedsThreshold_blocked() {
        createScene("order-anti", "订单反欺诈", "PULL", "USER", List.of("order"));
        createDecision("BLOCK", "拦截交易", 100, List.of());

        createMetric("daily-order-sum", "当日订单总额", "SQL_AGGREGATE", "DECIMAL",
                Map.of(
                        "datasource", "business-db",
                        "sql", "SELECT COALESCE(SUM(amount), 0) FROM orders WHERE user_id = :subjectId AND DATE(created_at) = CURDATE()"
                ), 60, false);

        Map<String, Object> conditionAst = Map.of(
                "type", "AndNode",
                "children", List.of(Map.of(
                        "type", "ConditionNode",
                        "conditionType", "GT",
                        "metricCode", "daily-order-sum",
                        "params", Map.of("threshold", 10000)
                ))
        );
        Map<String, Object> rule = createRule("order-anti", "daily-limit", "单日限额",
                conditionAst,
                List.of(Map.of("decisionCode", "BLOCK")),
                List.of("order"), "AST_BOOLEAN");
        publishRule(((Number) rule.get("ruleDefinitionId")).longValue());

        // u1: 15000 > 10000 → HIT
        Map<String, Object> hitResult = evaluate("order-anti", "order", "u1", Map.of());
        assertThat(hitResult.get("ruleHit")).isEqualTo(true);
        Map<String, Object> decision = (Map<String, Object>) hitResult.get("finalDecision");
        assertThat(decision.get("code")).isEqualTo("BLOCK");

        // u2: 500 < 10000 → MISS
        Map<String, Object> missResult = evaluate("order-anti", "order", "u2", Map.of());
        assertThat(missResult.get("ruleHit")).isEqualTo(false);

        // 评估审计异步落库；按本 scene 过滤，避免其他 test 异步残留干扰
        awaitRowCountWhere("evaluation_session", "scene_code='order-anti'", 2);
        List<Map<String, Object>> sessions = query(
                "SELECT subject_id, status FROM evaluation_session "
                        + "WHERE scene_code='order-anti' ORDER BY subject_id");
        assertThat(sessions.get(0).get("status").toString()).isEqualTo("HIT");
        assertThat(sessions.get(1).get("status").toString()).isEqualTo("MISS");
    }
}
