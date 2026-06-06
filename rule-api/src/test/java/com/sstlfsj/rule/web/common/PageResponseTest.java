package com.sstlfsj.rule.web.common;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PageResponseTest {

    @Test
    void of_填充各字段() {
        PageResponse<String> resp = PageResponse.of(List.of("a", "b"), 10L, 2, 20);

        assertThat(resp.items()).containsExactly("a", "b");
        assertThat(resp.total()).isEqualTo(10L);
        assertThat(resp.page()).isEqualTo(2);
        assertThat(resp.size()).isEqualTo(20);
    }

    @Test
    void of_空列表() {
        PageResponse<String> resp = PageResponse.of(List.of(), 0L, 1, 20);

        assertThat(resp.items()).isEmpty();
        assertThat(resp.total()).isZero();
    }
}
