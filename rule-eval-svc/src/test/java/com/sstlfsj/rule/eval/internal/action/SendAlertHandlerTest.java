package com.sstlfsj.rule.eval.internal.action;

import com.sstlfsj.rule.kernel.api.model.ActionContext;
import com.sstlfsj.rule.kernel.api.model.ActionResult;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** SendAlertHandler webhook 真实投递单测:成功/非2xx/连接失败/无URL/dryRun 不实发。 */
class SendAlertHandlerTest {

    private static ActionContext ctx() {
        return new ActionContext("a1", "SEND_ALERT", Map.of("level", "high"), null, 1L, "REJECT");
    }

    private static SendAlertHandler handler(String url) {
        SendAlertProperties props = new SendAlertProperties();
        props.setUrl(url);
        props.setTimeoutMs(2000);
        return new SendAlertHandler(props, new ObjectMapper());
    }

    @Test
    void execute_postsPayloadToWebhook_success() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        AtomicReference<String> body = new AtomicReference<>();
        server.createContext("/alert", ex -> {
            body.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            ActionResult r = handler("http://localhost:" + port + "/alert").execute(ctx());
            assertEquals(ActionResult.ActionStatus.SUCCESS, r.status());
            assertThat(body.get()).contains("SEND_ALERT").contains("REJECT");   // 载荷已 POST
        } finally {
            server.stop(0);
        }
    }

    @Test
    void execute_non2xx_returnsFailed() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/alert", ex -> {
            ex.sendResponseHeaders(500, -1);
            ex.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            ActionResult r = handler("http://localhost:" + port + "/alert").execute(ctx());
            assertEquals(ActionResult.ActionStatus.FAILED, r.status());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void execute_connectionFailure_returnsFailed() {
        // 指向未监听端口,连接失败 → FAILED(best-effort 不重试)
        ActionResult r = handler("http://localhost:1/alert").execute(ctx());
        assertEquals(ActionResult.ActionStatus.FAILED, r.status());
    }

    @Test
    void execute_noUrl_returnsSkipped() {
        ActionResult r = handler(null).execute(ctx());
        assertEquals(ActionResult.ActionStatus.SKIPPED, r.status());
        assertEquals("NO_WEBHOOK_URL", r.errorCode());
    }

    @Test
    void dryRun_doesNotSend_returnsSuccess() {
        // dryRun 指向连接失败 URL 仍返回 SUCCESS,证明未发起请求(否则会 FAILED)
        ActionResult r = handler("http://localhost:1/alert").dryRun(ctx());
        assertEquals(ActionResult.ActionStatus.SUCCESS, r.status());
    }
}
