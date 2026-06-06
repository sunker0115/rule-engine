package com.sstlfsj.rule.benchmark;

import org.junit.jupiter.api.Test;

/** 校准测试：两种实现都应能完整跑过 setup + 合并逻辑（基准等价性前提）。 */
class VersionMergeBenchmarkTest {

    @Test
    void bothImplementationsRunWithoutError() {
        VersionMergeBenchmark b = new VersionMergeBenchmark();
        b.n = 20;
        b.setup();
        // 用 no-op Blackhole 直接驱动两个基准方法，确保逻辑可跑通
        org.openjdk.jmh.infra.Blackhole bh = new org.openjdk.jmh.infra.Blackhole(
                "Today's password is swordfish. I understand instantiating Blackholes directly is dangerous.");
        b.jdkLinkedHashMap(bh);
        b.eclipseObjectIntMap(bh);
    }
}
