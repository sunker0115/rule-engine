package com.sstlfsj.rule.config.internal;

import com.baomidou.mybatisplus.extension.handlers.Jackson3TypeHandler;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;
import com.sstlfsj.rule.kernel.api.model.ast.ScoreBand;
import com.sstlfsj.rule.kernel.api.model.ast.ScorecardRootNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守护 Jackson3TypeHandler.setObjectMapper 注入：
 * 注入前裸 mapper 不含 AstNode 多态配置，ScorecardRootNode.bands 等子类专有字段丢失（e2e 暴露）；
 * 注入后序列化/反序列化应完整保留 bands。
 */
class JacksonConfigTest {

    private final JacksonConfig config = new JacksonConfig();

    @Test
    void jackson3TypeHandlerConfigurer_setsMapper() {
        var om = config.objectMapper();
        config.jackson3TypeHandlerConfigurer(om);
        assertThat(Jackson3TypeHandler.getObjectMapper()).isSameAs(om);
    }

    @Test
    void withInjectedMapper_scorecardBandsSurviveJsonRoundTrip() {
        var om = config.objectMapper();
        config.jackson3TypeHandlerConfigurer(om);

        ScorecardRootNode sc = new ScorecardRootNode(List.of(), 0.0,
                List.of(new ScoreBand(0, 60, "REJECT", "HIGH"),
                        new ScoreBand(60, 100, "PASS", "LOW")));

        // 模拟 Jackson3TypeHandler 序列化存库 → 读库反序列化
        String json = Jackson3TypeHandler.getObjectMapper().writeValueAsString(sc);
        assertThat(json).contains("bands").contains("REJECT");

        AstNode back = Jackson3TypeHandler.getObjectMapper().readValue(json, AstNode.class);
        assertThat(back).isInstanceOf(ScorecardRootNode.class);
        ScorecardRootNode recovered = (ScorecardRootNode) back;
        assertThat(recovered.bands()).hasSize(2);
        assertThat(recovered.bands().get(0).decisionCode()).isEqualTo("REJECT");
        assertThat(recovered.bands().get(1).category()).isEqualTo("LOW");
    }
}
