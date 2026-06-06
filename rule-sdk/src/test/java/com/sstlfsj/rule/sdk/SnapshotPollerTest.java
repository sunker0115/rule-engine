package com.sstlfsj.rule.sdk;

import com.sstlfsj.rule.kernel.internal.index.SceneRuleIndex;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SnapshotPollerTest {

    @Test
    void start_and_stop_withUnreachableServer_doesNotThrow() {
        SceneRuleIndex index = new SceneRuleIndex();
        SnapshotPoller poller = new SnapshotPoller(
                "http://localhost:19999", "t1",
                FetchMode.DECLARED, List.of("scene1"),
                Duration.ofHours(1), index);

        // 连接不存在的端口时 poll() 静默失败，start/stop 不应抛异常
        poller.start();
        poller.stop();

        assertThat(index.match("t1", "scene1", "ORDER")).isEmpty();
    }

    @Test
    void stop_withoutStart_doesNotThrow() {
        SnapshotPoller poller = new SnapshotPoller(
                "http://localhost:19999", "t1",
                FetchMode.ALL, List.of(),
                Duration.ofMinutes(5), new SceneRuleIndex());
        poller.stop();
    }
}
