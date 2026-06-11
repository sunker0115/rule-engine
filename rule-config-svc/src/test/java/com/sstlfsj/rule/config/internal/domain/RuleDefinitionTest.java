package com.sstlfsj.rule.config.internal.domain;

import com.sstlfsj.rule.kernel.api.model.RuleKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 验证 RuleDefinition Lombok getter/setter 及字段覆盖。 */
class RuleDefinitionTest {

    @Test
    void getterSetter_roundTrip() {
        RuleDefinition def = new RuleDefinition();
        def.setId(10L);
        def.setTenantId(1L);
        def.setSceneId(5L);
        def.setCode("rule.demo");
        def.setName("测试规则");
        def.setDescription("规则描述");
        def.setStatus(RuleDefinitionStatus.DRAFT);
        def.setKind(RuleKind.AST_BOOLEAN);
        def.setCurrentVersion(99L);
        def.setPublishedBy("operator1");
        def.setCreatedBy("operator1");
        def.setUpdatedBy("operator2");

        assertEquals(10L, def.getId());
        assertEquals(1L, def.getTenantId());
        assertEquals(5L, def.getSceneId());
        assertEquals("rule.demo", def.getCode());
        assertEquals("测试规则", def.getName());
        assertEquals("规则描述", def.getDescription());
        assertEquals(RuleDefinitionStatus.DRAFT, def.getStatus());
        assertEquals(RuleKind.AST_BOOLEAN, def.getKind());
        assertEquals(99L, def.getCurrentVersion());
        assertEquals("operator1", def.getPublishedBy());
        assertEquals("operator1", def.getCreatedBy());
        assertEquals("operator2", def.getUpdatedBy());
    }

    @Test
    void defaultValues_areNull() {
        RuleDefinition def = new RuleDefinition();
        assertNull(def.getId());
        assertNull(def.getCurrentVersion());
        assertNull(def.getPublishedAt());
        assertNull(def.getCreatedAt());
    }

    @Test
    void draft_setsBusinessDefaults() {
        RuleDefinition def = RuleDefinition.draft(1L, 5L, "rule.demo", "测试规则", RuleKind.AST_BOOLEAN, "operator1");

        assertEquals(1L, def.getTenantId());
        assertEquals(5L, def.getSceneId());
        assertEquals("rule.demo", def.getCode());
        assertEquals("测试规则", def.getName());
        assertEquals(RuleKind.AST_BOOLEAN, def.getKind());
        assertEquals("operator1", def.getCreatedBy());
        // 草稿默认：status=DRAFT、createdAt 已赋值
        assertEquals(RuleDefinitionStatus.DRAFT, def.getStatus());
        assertNotNull(def.getCreatedAt());
        // 未涉及字段保持 null
        assertNull(def.getId());
        assertNull(def.getCurrentVersion());
        assertNull(def.getPublishedAt());
    }
}
