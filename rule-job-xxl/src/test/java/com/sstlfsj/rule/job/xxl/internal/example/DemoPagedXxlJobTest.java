package com.sstlfsj.rule.job.xxl.internal.example;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 xxl 原生写法分页例子的自驱翻页：每页 2 条、page≥3 返空批停止，共处理 3 页 × 2 = 6 条。 */
class DemoPagedXxlJobTest {

    @Test
    void scansAllPagesUntilEmptyBatch() {
        int processed = new DemoPagedXxlJob().scanAllPages();

        assertThat(processed).isEqualTo(6);
    }
}
