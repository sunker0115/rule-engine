package com.sstlfsj.rule.web.admin.convert;

import com.sstlfsj.rule.config.internal.domain.DecisionStrategy;
import com.sstlfsj.rule.config.internal.domain.DominantMode;
import com.sstlfsj.rule.config.internal.domain.SceneDef;
import com.sstlfsj.rule.config.internal.domain.SceneStatus;
import com.sstlfsj.rule.kernel.api.model.SubjectType;
import com.sstlfsj.rule.web.admin.dto.SceneResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 MapStruct 生成的 SceneConvert 正确映射所有字段。 */
class SceneConvertTest {

    private final SceneConvert convert = new SceneConvertImpl();

    @Test
    void toResponse_mapsAllFields() {
        SceneDef scene = new SceneDef();
        scene.setId(1L);
        scene.setTenantId(100L);
        scene.setCode("PAYMENT");
        scene.setName("支付场景");
        scene.setDescription("处理支付事件");
        scene.setDominantMode(DominantMode.PUSH);
        scene.setDecisionStrategy(DecisionStrategy.HIGHEST_PRIORITY);
        scene.setSubjectType(SubjectType.USER);
        scene.setStatus(SceneStatus.ACTIVE);

        SceneResponse resp = convert.toResponse(scene);

        assertThat(resp.getId()).isEqualTo(1L);
        assertThat(resp.getTenantId()).isEqualTo(100L);
        assertThat(resp.getCode()).isEqualTo("PAYMENT");
        assertThat(resp.getName()).isEqualTo("支付场景");
        assertThat(resp.getDescription()).isEqualTo("处理支付事件");
        assertThat(resp.getDominantMode()).isEqualTo("PUSH");
        assertThat(resp.getDecisionStrategy()).isEqualTo("HIGHEST_PRIORITY");
        assertThat(resp.getSubjectType()).isEqualTo("USER");
        assertThat(resp.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void toResponse_nullInput_returnsNull() {
        assertThat(convert.toResponse(null)).isNull();
    }

    @Test
    void toResponse_partialFields_unmappedFieldsAreNull() {
        SceneDef scene = new SceneDef();
        scene.setId(2L);
        scene.setCode("RISK");

        SceneResponse resp = convert.toResponse(scene);

        assertThat(resp.getId()).isEqualTo(2L);
        assertThat(resp.getCode()).isEqualTo("RISK");
        assertThat(resp.getName()).isNull();
        assertThat(resp.getStatus()).isNull();
    }
}
