package com.sstlfsj.rule.eval.internal.metric.http;

import com.sstlfsj.rule.eval.internal.metric.sql.FetchResourceProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 凭证库：把连接器 auth 的 *Ref 引用名解析为实际密钥。
 * 凭证由配置（env/secrets）经 {@link FetchResourceProperties#getCredentials()} 注入，不落 metric/connector。
 */
@Component
public class CredentialStore {

    private final Map<String, String> credentials = new HashMap<>();

    public CredentialStore(FetchResourceProperties props) {
        for (FetchResourceProperties.CredentialDef def : props.getCredentials()) {
            credentials.put(def.getName(), def.getValue());
        }
    }

    /**
     * 按引用名取凭证值。
     *
     * @param ref 凭证引用名（连接器 auth 的 *Ref）
     * @return 凭证值；未配置返回 null
     */
    public String get(String ref) {
        return credentials.get(ref);
    }
}
