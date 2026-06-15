package com.sstlfsj.rule.config.api.connector;

/**
 * 静态请求头鉴权。
 *
 * @param headerName    头名，如 "X-Api-Key"
 * @param credentialRef infra 凭证引用名（值不落描述符）
 */
public record StaticHeaderAuth(String headerName, String credentialRef) implements AuthScheme {
    @Override public AuthKind kind() { return AuthKind.STATIC_HEADER; }
}
