package com.sstlfsj.rule.web.admin.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 SceneResponse Lombok @Data 生成的 getter/setter/equals/hashCode。 */
class SceneResponseTest {

    @Test
    void getterSetter_roundTrip() {
        SceneResponse resp = new SceneResponse();
        resp.setId(1L);
        resp.setTenantId(100L);
        resp.setCode("PAYMENT");
        resp.setName("支付场景");
        resp.setDescription("描述");
        resp.setDominantMode("PUSH");
        resp.setDecisionStrategy("HIGHEST_PRIORITY");
        resp.setSubjectType("USER");
        resp.setStatus("ACTIVE");

        assertThat(resp.getId()).isEqualTo(1L);
        assertThat(resp.getTenantId()).isEqualTo(100L);
        assertThat(resp.getCode()).isEqualTo("PAYMENT");
        assertThat(resp.getName()).isEqualTo("支付场景");
        assertThat(resp.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void equals_sameFields_areEqual() {
        SceneResponse a = new SceneResponse();
        a.setId(1L);
        a.setCode("PAYMENT");

        SceneResponse b = new SceneResponse();
        b.setId(1L);
        b.setCode("PAYMENT");

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }
}
