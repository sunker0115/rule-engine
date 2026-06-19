package com.sstlfsj.rule.job.internal.runner;

import com.google.common.hash.Hashing;

import java.nio.charset.StandardCharsets;

/**
 * 合成 RuleEvent 的幂等 eventId 计算：{@code murmur3_128(taskRunId + ":" + subjectId)}。
 *
 * <p>同一 taskRun + 同一 subject 必得稳定 eventId，落 evaluation_session 的
 * {@code uk_tenant_event(tenant_id, event_id)} 做幂等去重（D11 / §3.10）。
 */
final class EventIdHasher {

    private EventIdHasher() {
    }

    static String hash(long taskRunId, String subjectId) {
        return Hashing.murmur3_128()
                .newHasher()
                .putLong(taskRunId)
                .putString(":" + subjectId, StandardCharsets.UTF_8)
                .hash()
                .toString();
    }
}
