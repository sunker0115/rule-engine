package com.sstlfsj.rule.eval.internal.metric.http;

/**
 * 连接器鉴权所需凭证未在凭证库中配置时抛出。handler 据此把取数结果归为 UNAUTHORIZED。
 */
public class CredentialMissingException extends RuntimeException {

    /**
     * @param ref 缺失的凭证引用名
     */
    public CredentialMissingException(String ref) {
        super("missing credential for ref: " + ref);
    }
}
