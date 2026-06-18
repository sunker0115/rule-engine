package com.sstlfsj.rule.conformance;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 连接器一致性套件（连接器行为契约的可执行规约，对照 {@code docs/04-extension.md §9.1} 带编号 Requirement）。
 * 自验逻辑：跑覆盖成功 + 各失败码的黄金用例，全绿即证明套件参照实现与契约一致；
 * 同时反向验证——人为构造与契约不符的期望，套件应抛 AssertionError。
 *
 * <p><b>怎么跑</b>：{@code $MVN -pl rule-eval-svc -am test -Dtest=ConformanceSuiteTest}（JDK25 环境，见 mvn-env），
 * 或随 eval-svc 全量测试一起跑。</p>
 *
 * <p><b>怎么加用例</b>：往 {@code runsGoldenCasesCoveringSuccessAndFailureCodes} 的 {@code cases} 列表加一个 {@link GoldenCase}：</p>
 * <pre>{@code
 * // 成功：名字, 路径, 上游响应体, valuePath(点号路径), 期望标量值
 * GoldenCase.success("success-scalar", "/score", "{\"data\":{\"score\":42}}", "data.score", 42L)
 * // 失败：名字, 路径, HTTP 状态码, 响应体, valuePath, 期望归一错误码
 * GoldenCase.failure("upstream-500", "/down", 500, "{}", "data.score", "UPSTREAM_ERROR")
 * }</pre>
 * 新增 §9.1 编号 Requirement 时在此补一条对应用例交叉验证。
 *
 * <p><b>边界</b>：{@link ConformanceSuite} 内是一份"标准连接器 HTTP 语义参照实现"，并非直跑真实
 * {@code DeclarativeHttpConnectorHandler}——验证的是"契约自洽"，非"真 handler 符合契约"，两者同口径但分立、可能漂移。</p>
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
