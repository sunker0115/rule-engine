package com.sstlfsj.rule.config.api.connector;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** 连接器鉴权方案（typed 多态，按 kind 判别）。密钥以 *Ref 引用 infra，不内联明文。 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = StaticHeaderAuth.class, name = "STATIC_HEADER"),
        @JsonSubTypes.Type(value = BearerAuth.class, name = "BEARER"),
        @JsonSubTypes.Type(value = OAuth2ClientCredentialsAuth.class, name = "OAUTH2_CLIENT_CREDENTIALS")
})
public sealed interface AuthScheme permits StaticHeaderAuth, BearerAuth, OAuth2ClientCredentialsAuth {
    /** @return 鉴权种类。 */
    AuthKind kind();
}
