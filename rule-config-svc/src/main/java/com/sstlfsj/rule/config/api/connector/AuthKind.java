package com.sstlfsj.rule.config.api.connector;

/** 连接器鉴权方案种类（封闭取值，作 AuthScheme 多态判别）。 */
public enum AuthKind {
    STATIC_HEADER, BEARER, OAUTH2_CLIENT_CREDENTIALS
}
