package com.sstlfsj.rule.sdk.starter;

import com.sstlfsj.rule.sdk.FetchMode;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/** rule.sdk.* 配置属性。 */
@Getter
@Setter
@ConfigurationProperties(prefix = "rule.sdk")
public class SdkProperties {

    /** rule-api 服务地址，必填。 */
    private String serverUrl;
    /** 租户 ID，必填。 */
    private String tenantId;
    /** 快照拉取模式，默认 DECLARED。 */
    private FetchMode fetchMode = FetchMode.DECLARED;
    /** fetchMode=DECLARED 时要订阅的场景列表。 */
    private List<String> scenes = List.of();
    /** 轮询间隔，默认 30 秒。 */
    private Duration pollInterval = Duration.ofSeconds(30);
    /** JSON 规则文件路径列表（文件模式），如 classpath:rules/fraud.json。与 serverUrl 互斥。 */
    private List<String> ruleFiles = List.of();
}
