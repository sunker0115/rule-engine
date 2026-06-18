package com.sstlfsj.rule.config.api.connector;

/**
 * Bearer token 鉴权。
 *
 * @param tokenRef infra 凭证引用名
 */
public record BearerAuth(String tokenRef) implements AuthScheme {
    @Override public AuthKind kind() { return AuthKind.BEARER; }
}
