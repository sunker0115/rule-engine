package com.sstlfsj.rule.eval.internal.domain;

/** evaluation_session 的 status 取值（D22 第四态含 BLOCKED）。 */
public enum SessionStatus { PENDING, HIT, MISS, BLOCKED, ERROR, FAILED }
