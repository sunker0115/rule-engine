package com.sstlfsj.rule.eval.internal.action;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** SEND_ALERT webhook 投递配置(best-effort,失败不重试)。 */
@Getter
@Setter
@ConfigurationProperties(prefix = "engine.rule.action.send-alert")
public class SendAlertProperties {
    /** webhook URL;为空则不实发(execute 返回 skipped)。 */
    private String url;
    /** 连接 + 请求超时毫秒(默认 2000,短超时 best-effort)。 */
    private long timeoutMs = 2000;
}
