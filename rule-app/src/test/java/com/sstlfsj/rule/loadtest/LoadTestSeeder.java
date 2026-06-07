package com.sstlfsj.rule.loadtest;

import com.sstlfsj.rule.config.api.dto.DraftCreatedResult;
import com.sstlfsj.rule.config.api.service.ConfigService;
import com.sstlfsj.rule.config.api.service.MetricWriteService;
import com.sstlfsj.rule.config.api.service.MetricWriteService.MetricWriteCommand;
import com.sstlfsj.rule.config.api.service.SceneService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

/**
 * 压测数据播种器（手动触发）：经已校验写服务造 scene+metric+规则。
 *
 * <p>每个 {@code seedN} 先按租户清理再播种，可重跑。所有规则挂同一 (scene, eventType)，
 * 候选数 = N（探"候选数→评估成本"维度）。metric 用 ATTRIBUTE 源（发布期不校验 datasource，
 * 值由请求 providedMetrics 提供）。规则为 AST_BOOLEAN：{@code demo.score GTE 0}，
 * 请求 providedMetrics 传 100 → 恒命中。
 */
@SpringBootTest
@Tag("loadtest")
class LoadTestSeeder {

    static final String TENANT = "9001";          // 专用压测租户，清理按此 DELETE
    static final String SCENE = "loadtest";
    static final String EVENT_TYPE = "login";
    static final String METRIC = "demo.score";
    static final String ACTOR = "loadtest";

    @Autowired SceneService sceneService;
    @Autowired MetricWriteService metricWriteService;
    @Autowired ConfigService configService;
    @Autowired DataSource dataSource;

    /** AST：demo.score GTE 0（provided 100 恒命中）。 */
    static String conditionAstJson() {
        return "{\"type\":\"ConditionNode\",\"conditionType\":\"GTE\","
             + "\"metricCode\":\"" + METRIC + "\",\"displayLabel\":\"score>=0\","
             + "\"params\":{\"threshold\":0},\"dataType\":\"LONG\"}";
    }

    /** 按租户清理压测数据（FK 序：rule_version→rule_definition→metric_definition→scene），可重跑。 */
    void cleanup() {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("DELETE rv FROM rule_version rv JOIN rule_definition rd "
                    + "ON rv.rule_definition_id=rd.id WHERE rd.tenant_id=" + TENANT);
            st.executeUpdate("DELETE FROM rule_definition WHERE tenant_id=" + TENANT);
            st.executeUpdate("DELETE FROM metric_definition WHERE tenant_id=" + TENANT);
            st.executeUpdate("DELETE FROM scene WHERE tenant_id=" + TENANT);
        } catch (SQLException e) {
            throw new RuntimeException("压测数据清理失败", e);
        }
    }

    /** 生成 ruleCount 条全命中规则（同 scene/eventType，均 publish 为 ACTIVE）。 */
    void seedRules(int ruleCount) {
        cleanup();
        sceneService.createScene(TENANT, SCENE, "Load Test Scene", null,
                "HYBRID", "USER", "[\"" + EVENT_TYPE + "\"]", null, null, ACTOR);
        metricWriteService.create(Long.valueOf(TENANT), METRIC,
                new MetricWriteCommand("demo score", "ATTRIBUTE", "LONG", Map.of(), null, true), ACTOR);
        for (int i = 1; i <= ruleCount; i++) {
            DraftCreatedResult draft = configService.createDraft(TENANT, SCENE,
                    "lt-rule-" + i, "lt rule " + i,
                    conditionAstJson(), "[{\"decisionCode\":\"PASS\",\"priority\":1}]",
                    "[]", "[\"" + EVENT_TYPE + "\"]", "AST_BOOLEAN", ACTOR);
            configService.publish(TENANT, draft.ruleDefinitionId(), ACTOR);
        }
    }

    @Test void seed10()  { seedRules(10);  }
    @Test void seed50()  { seedRules(50);  }
    @Test void seed200() { seedRules(200); }
}
