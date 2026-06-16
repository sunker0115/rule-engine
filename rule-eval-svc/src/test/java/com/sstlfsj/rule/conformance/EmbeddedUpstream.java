package com.sstlfsj.rule.conformance;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/**
 * 嵌入式 mock 上游：包装一个随机端口的 WireMock 服务，供一致性套件按黄金用例起桩、
 * 暴露 baseUrl、并在用例结束后校验上游确实被请求过。接入方无需真实上游即可独立跑套件。
 */
public final class EmbeddedUpstream implements AutoCloseable {

    private final WireMockServer server;

    /** 在随机空闲端口启动 mock 上游。 */
    public EmbeddedUpstream() {
        this.server = new WireMockServer(options().dynamicPort());
        this.server.start();
    }

    /**
     * 按黄金用例起一条 GET 桩：路径命中即返回桩状态 + 桩体（JSON）。
     *
     * @param testCase 黄金用例
     */
    public void stub(GoldenCase testCase) {
        server.stubFor(WireMock.get(WireMock.urlEqualTo(testCase.stubPath()))
                .willReturn(WireMock.aResponse()
                        .withStatus(testCase.stubStatus())
                        .withHeader("Content-Type", "application/json")
                        .withBody(testCase.stubBody())));
    }

    /** @return 上游 baseUrl（含动态端口，如 http://localhost:54321）。 */
    public String baseUrl() {
        return server.baseUrl();
    }

    /**
     * 校验某路径恰好被 GET 请求过一次（确认套件确实发了真实 HTTP，而非短路返回）。
     *
     * @param path 期望被请求的路径
     */
    public void verifyRequested(String path) {
        server.verify(1, WireMock.getRequestedFor(WireMock.urlEqualTo(path)));
    }

    /** 清空已注册的桩与请求记录（用例间隔离）。 */
    public void reset() {
        server.resetAll();
    }

    /** 停止 mock 上游，释放端口。 */
    @Override
    public void close() {
        server.stop();
    }
}
