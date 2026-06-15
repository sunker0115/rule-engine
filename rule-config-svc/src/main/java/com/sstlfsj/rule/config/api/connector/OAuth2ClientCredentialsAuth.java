package com.sstlfsj.rule.config.api.connector;

import lombok.Builder;
import java.util.List;

/**
 * OAuth2 client-credentials 鉴权。token 由 eval 侧按 *Ref 取凭证后换取并缓存（P2 实现）。
 *
 * @param tokenUrl        取 token 的 URL
 * @param clientIdRef     clientId 凭证引用名
 * @param clientSecretRef clientSecret 凭证引用名
 * @param scopes          申请 scope 列表
 */
@Builder
public record OAuth2ClientCredentialsAuth(
        String tokenUrl, String clientIdRef, String clientSecretRef, List<String> scopes) implements AuthScheme {
    @Override public AuthKind kind() { return AuthKind.OAUTH2_CLIENT_CREDENTIALS; }
}
