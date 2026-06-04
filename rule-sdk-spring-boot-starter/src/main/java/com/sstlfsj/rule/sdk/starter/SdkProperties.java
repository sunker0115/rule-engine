package com.sstlfsj.rule.sdk.starter;

import com.sstlfsj.rule.sdk.FetchMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/** rule.sdk.* 配置属性。 */
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

    public String getServerUrl()            { return serverUrl; }
    public void setServerUrl(String v)      { this.serverUrl = v; }
    public String getTenantId()             { return tenantId; }
    public void setTenantId(String v)       { this.tenantId = v; }
    public FetchMode getFetchMode()         { return fetchMode; }
    public void setFetchMode(FetchMode v)   { this.fetchMode = v; }
    public List<String> getScenes()         { return scenes; }
    public void setScenes(List<String> v)   { this.scenes = v; }
    public Duration getPollInterval()       { return pollInterval; }
    public void setPollInterval(Duration v) { this.pollInterval = v; }
}
