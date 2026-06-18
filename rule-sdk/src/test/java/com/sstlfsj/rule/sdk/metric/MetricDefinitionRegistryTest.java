package com.sstlfsj.rule.sdk.metric;

import com.sstlfsj.rule.kernel.api.model.MetricDescriptor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class MetricDefinitionRegistryTest {

    /** 版本默认为 1 的描述符。 */
    private static MetricDescriptor desc(String code) {
        return new MetricDescriptor(code, 1, "TEST", "LONG", false, 0, Map.of());
    }

    /** 指定版本的描述符。 */
    private static MetricDescriptor desc(String code, int version) {
        return new MetricDescriptor(code, version, "TEST", "LONG", false, 0, Map.of());
    }

    @Test
    void put_then_get_returnsDescriptor() {
        MetricDefinitionRegistry registry = new MetricDefinitionRegistry();
        registry.put("t1", desc("risk.score"));
        assertThat(registry.get("t1", "risk.score", 1).metricCode()).isEqualTo("risk.score");
    }

    @Test
    void get_missing_returnsNull() {
        assertThat(new MetricDefinitionRegistry().get("t1", "nope", 1)).isNull();
    }

    @Test
    void get_otherTenant_returnsNull() {
        MetricDefinitionRegistry registry = new MetricDefinitionRegistry();
        registry.put("t1", desc("risk.score"));
        assertThat(registry.get("t2", "risk.score", 1)).isNull();
    }

    @Test
    void get_wrongVersion_returnsNull() {
        MetricDefinitionRegistry registry = new MetricDefinitionRegistry();
        registry.put("t1", desc("risk.score", 1));
        // 注册的是 version=1，查 version=2 应返回 null
        assertThat(registry.get("t1", "risk.score", 2)).isNull();
    }

    @Test
    void put_multipleVersions_retrievedIndependently() {
        MetricDefinitionRegistry registry = new MetricDefinitionRegistry();
        registry.put("t1", desc("risk.score", 1));
        registry.put("t1", desc("risk.score", 2));
        assertThat(registry.get("t1", "risk.score", 1).metricVersion()).isEqualTo(1);
        assertThat(registry.get("t1", "risk.score", 2).metricVersion()).isEqualTo(2);
    }

    @Test
    void replaceAll_removesStaleEntries() {
        MetricDefinitionRegistry registry = new MetricDefinitionRegistry();
        registry.put("t1", desc("old.metric"));
        registry.replaceAll("t1", List.of(desc("new.metric")));
        assertThat(registry.get("t1", "old.metric", 1)).isNull();
        assertThat(registry.get("t1", "new.metric", 1)).isNotNull();
    }

    @Test
    void replaceAll_removesAllVersionsOfTenant() {
        MetricDefinitionRegistry registry = new MetricDefinitionRegistry();
        registry.put("t1", desc("risk.score", 1));
        registry.put("t1", desc("risk.score", 2));
        registry.replaceAll("t1", List.of(desc("risk.score", 3)));
        assertThat(registry.get("t1", "risk.score", 1)).isNull();
        assertThat(registry.get("t1", "risk.score", 2)).isNull();
        assertThat(registry.get("t1", "risk.score", 3)).isNotNull();
    }

    @Test
    void replaceAll_doesNotTouchOtherTenant() {
        MetricDefinitionRegistry registry = new MetricDefinitionRegistry();
        registry.put("t2", desc("keep.metric"));
        registry.replaceAll("t1", List.of(desc("new.metric")));
        assertThat(registry.get("t2", "keep.metric", 1)).isNotNull();
    }

    @Test
    void replaceAll_emptyList_clearsTenant() {
        MetricDefinitionRegistry registry = new MetricDefinitionRegistry();
        registry.put("t1", desc("a"));
        registry.replaceAll("t1", List.of());
        assertThat(registry.get("t1", "a", 1)).isNull();
    }

    /**
     * 回归 #8：热更窗口内 get() 不得读到"旧删新未写"的中间态。
     * M1 在每一轮 replaceAll 的新旧列表中都存在 → 并发 get 必须始终命中。
     * copy-on-write 下读到的永远是完整快照；旧的 removeIf+put 两段写会瞬时缺 M1。
     */
    @Test
    void replaceAll_concurrentGet_neverSeesTornMissingEntry() throws Exception {
        MetricDefinitionRegistry registry = new MetricDefinitionRegistry();
        registry.replaceAll("t1", List.of(desc("M1", 1)));
        AtomicBoolean sawNull = new AtomicBoolean(false);
        AtomicBoolean running = new AtomicBoolean(true);

        Thread reader = new Thread(() -> {
            while (running.get()) {
                if (registry.get("t1", "M1", 1) == null) {
                    sawNull.set(true);
                    break;
                }
            }
        });
        reader.start();
        // 每轮都用含 M1 的新列表整体替换（外加一个变动项制造写压力）
        for (int i = 0; i < 5000; i++) {
            registry.replaceAll("t1", List.of(desc("M1", 1), desc("M" + i, 1)));
        }
        running.set(false);
        reader.join();

        assertThat(sawNull).as("M1 在新旧列表中始终存在，并发 get 不应读到 null").isFalse();
    }
}
