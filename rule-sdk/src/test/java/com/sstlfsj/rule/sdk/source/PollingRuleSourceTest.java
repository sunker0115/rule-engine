package com.sstlfsj.rule.sdk.source;

import com.sstlfsj.rule.kernel.internal.index.SceneRuleIndex;
import com.sstlfsj.rule.sdk.FetchMode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatNoException;

class PollingRuleSourceTest {

    private static PollingRuleSource source() {
        return new PollingRuleSource(
                "http://localhost:19999", "t1",
                FetchMode.DECLARED, List.of("fraud"),
                Duration.ofHours(1));
    }

    @Test
    void loadInto_startsPoller_connectFailureSilent() {
        // 连接不存在的端口，SnapshotPoller 静默失败，索引保持空
        SceneRuleIndex index = new SceneRuleIndex();
        PollingRuleSource src = source();
        assertThatNoException().isThrownBy(() -> src.loadInto(index));
        src.stop();
    }

    @Test
    void stop_beforeLoadInto_doesNotThrow() {
        // stop() 在 loadInto() 之前调用，poller 为 null，不应抛异常
        assertThatNoException().isThrownBy(() -> source().stop());
    }
}
