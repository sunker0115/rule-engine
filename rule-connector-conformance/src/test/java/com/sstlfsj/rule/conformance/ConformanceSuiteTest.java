package com.sstlfsj.rule.conformance;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 一致性套件自验：跑覆盖成功 + 各失败码的黄金用例，全绿即证明套件参照实现与连接器行为契约一致。
 * 同时反向验证——人为构造与契约不符的期望，套件应抛 AssertionError。
 */
class ConformanceSuiteTest {

    private EmbeddedUpstream upstream;
    private ConformanceSuite suite;

    @BeforeEach
    void setUp() {
        upstream = new EmbeddedUpstream();
        suite = new ConformanceSuite();
    }

    @AfterEach
    void tearDown() {
        upstream.close();
    }

    @Test
    void runsGoldenCasesCoveringSuccessAndFailureCodes() {
        List<GoldenCase> cases = List.of(
                // 成功：2xx + valuePath 命中标量
                GoldenCase.success("success-scalar", "/score",
                        "{\"data\":{\"score\":42}}", "data.score", 42L),
                // UPSTREAM_ERROR：上游 500
                GoldenCase.failure("upstream-500", "/down", 500,
                        "{}", "data.score", "UPSTREAM_ERROR"),
                // UNAUTHORIZED：上游 401
                GoldenCase.failure("unauthorized-401", "/secure", 401,
                        "{}", "data.score", "UNAUTHORIZED"),
                // UNAUTHORIZED：上游 403 同归一
                GoldenCase.failure("forbidden-403", "/forbidden", 403,
                        "{}", "data.score", "UNAUTHORIZED"),
                // PARSE_ERROR：valuePath 在 200 响应里未命中
                GoldenCase.failure("parse-missing-path", "/empty", 200,
                        "{\"data\":{}}", "data.score", "PARSE_ERROR"),
                // PARSE_ERROR：200 响应体非合法 JSON
                GoldenCase.failure("parse-bad-json", "/garbage", 200,
                        "not-json", "data.score", "PARSE_ERROR"));

        suite.run(upstream, cases);
    }

    @Test
    void failsWhenExpectationViolatesContract() {
        // 上游桩真返回 500（契约归一 UPSTREAM_ERROR），但用例错误期望成功映射值 → 套件应抛断言失败。
        // 直接用规范构造器制造「stub 状态与期望矛盾」的用例（success()/failure() 工厂不会产出此组合）。
        GoldenCase wrong = new GoldenCase("wrong-expectation", "/down", 500,
                "{}", "data.score", 1L, null);

        assertThatThrownBy(() -> suite.run(upstream, List.of(wrong)))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void embeddedUpstreamExposesBaseUrl() {
        assertThat(upstream.baseUrl()).startsWith("http://").contains("localhost");
    }
}
