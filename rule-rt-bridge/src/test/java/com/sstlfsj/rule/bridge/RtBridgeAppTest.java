package com.sstlfsj.rule.bridge;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RtBridgeAppTest {

    // 冒烟：bean 工厂方法装配 EvalClient（不起 Spring 上下文，避免依赖真实 Kafka broker）
    @Test
    void evalClientBeanIsAssembled() {
        EvalClient client = new RtBridgeApp()
                .evalClient("http://localhost:8080", "t1", "trading.scene_b", "trade.suspect");
        assertThat(client).isNotNull();
    }
}
