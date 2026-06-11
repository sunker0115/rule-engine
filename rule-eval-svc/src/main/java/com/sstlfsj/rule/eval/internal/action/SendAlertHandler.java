package com.sstlfsj.rule.eval.internal.action;

import com.sstlfsj.rule.kernel.api.annotation.ActionType;
import com.sstlfsj.rule.kernel.api.model.ActionContext;
import com.sstlfsj.rule.kernel.api.model.ActionResult;
import com.sstlfsj.rule.kernel.api.spi.action.ActionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * 发送告警 ActionHandler:execute 投递真实 HTTP webhook(best-effort,失败不重试);dryRun 仅预览不实发。
 * URL/超时由 {@link SendAlertProperties}(engine.rule.action.send-alert.*)配置;URL 为空则跳过。
 */
@Component
@ActionType("SEND_ALERT")
public class SendAlertHandler implements ActionHandler {

    private static final Logger log = LoggerFactory.getLogger(SendAlertHandler.class);

    private final SendAlertProperties props;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public SendAlertHandler(SendAlertProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getTimeoutMs()))
                .build();
    }

    /**
     * 投递告警 webhook:POST 告警载荷到配置 URL。
     * 2xx → SUCCESS;非 2xx / 连接失败 / 超时 → FAILED(best-effort,不重试);URL 为空 → SKIPPED。
     *
     * @param ctx 动作执行上下文
     * @return 执行结果
     */
    @Override
    public ActionResult execute(ActionContext ctx) {
        if (props.getUrl() == null || props.getUrl().isBlank()) {
            return ActionResult.skipped(ctx.actionId(), ctx.actionType(), "NO_WEBHOOK_URL");
        }
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "actionId", ctx.actionId(),
                    "actionType", ctx.actionType(),
                    "decisionCode", ctx.decisionCode() == null ? "" : ctx.decisionCode(),
                    "params", ctx.params()));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(props.getUrl()))
                    .timeout(Duration.ofMillis(props.getTimeoutMs()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<Void> resp = httpClient.send(req, HttpResponse.BodyHandlers.discarding());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                return ActionResult.success(ctx.actionId(), ctx.actionType());
            }
            log.warn("SEND_ALERT webhook 非 2xx,丢弃(best-effort): status={} url={}", resp.statusCode(), props.getUrl());
            return ActionResult.failed(ctx.actionId(), ctx.actionType(), "ALERT_HTTP_" + resp.statusCode(), false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("SEND_ALERT webhook 投递被中断(best-effort 不重试): {}", e.getMessage());
            return ActionResult.failed(ctx.actionId(), ctx.actionType(), "ALERT_DELIVERY_INTERRUPTED", false);
        } catch (Exception e) {
            log.warn("SEND_ALERT webhook 投递失败(best-effort 不重试): {}", e.getMessage());
            return ActionResult.failed(ctx.actionId(), ctx.actionType(), "ALERT_DELIVERY_FAILED", false);
        }
    }

    /**
     * dry-run 预览发送告警:不实际投递 webhook,直接返回成功预览。
     *
     * @param ctx 动作执行上下文
     * @return 预览结果,status=SUCCESS
     */
    @Override
    public ActionResult dryRun(ActionContext ctx) {
        return ActionResult.success(ctx.actionId(), ctx.actionType());
    }
}
