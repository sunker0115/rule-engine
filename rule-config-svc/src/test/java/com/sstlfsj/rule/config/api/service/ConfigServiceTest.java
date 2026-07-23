package com.sstlfsj.rule.config.api.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sstlfsj.rule.config.api.dto.DraftCreatedResult;
import com.sstlfsj.rule.config.api.dto.RuleContent;
import com.sstlfsj.rule.config.api.dto.RuleDetailVO;
import com.sstlfsj.rule.config.api.dto.RuleListQuery;
import com.sstlfsj.rule.config.api.dto.RuleVersionContentVO;
import com.sstlfsj.rule.config.internal.domain.RuleDefinition;
import com.sstlfsj.rule.config.api.dto.TenantItemVO;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证 ConfigService 契约：方法签名可编译，Stub 实现能正确抛出 UnsupportedOperationException。 */
class ConfigServiceTest {

    private final ConfigService stub = new ConfigService() {
        @Override
        public RuleVersionSnapshot publish(Long tenantId, Long ruleDefinitionId, String actorId) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public void disable(Long tenantId, Long ruleDefinitionId, String actorId) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public void enable(Long tenantId, Long ruleDefinitionId, String actorId) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public Page<RuleDefinition> listRules(RuleListQuery q) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public DraftCreatedResult createDraft(Long tenantId, String sceneCode,
                String code, RuleContent content, String actorId) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public RuleDetailVO getRuleDetail(Long tenantId, Long ruleId) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public RuleVersionContentVO getRuleVersion(Long tenantId, Long ruleId, Long versionId) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public DraftCreatedResult editDraft(Long tenantId, Long ruleId, RuleContent content, String actorId) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public DraftCreatedResult newVersion(Long tenantId, Long ruleId, RuleContent content,
                Long fromVersionId, String actorId) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public void deleteRule(Long tenantId, Long ruleId, String actorId) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public void deleteDraftVersion(Long tenantId, Long ruleId, Long versionId, String actorId) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public List<TenantItemVO> listTenants(String keyword, String status) {
            throw new UnsupportedOperationException("stub");
        }
        @Override public void toggleTenantStatus(Long tenantId, boolean enable) { throw new UnsupportedOperationException("stub"); }
    };

    @Test
    void publish_stubThrowsUnsupported() {
        assertThrows(UnsupportedOperationException.class,
                () -> stub.publish(1L, 1L, "actor"));
    }

    @Test
    void disable_stubThrowsUnsupported() {
        assertThrows(UnsupportedOperationException.class,
                () -> stub.disable(1L, 1L, "actor"));
    }

    @Test
    void enable_stubThrowsUnsupported() {
        assertThrows(UnsupportedOperationException.class,
                () -> stub.enable(1L, 1L, "actor"));
    }

    @Test
    void listRules_stubThrowsUnsupported() {
        assertThrows(UnsupportedOperationException.class,
                () -> stub.listRules(new RuleListQuery(1L, null, null, null, null, 1, 20)));
    }

    @Test
    void listTenants_stubThrowsUnsupported() {
        assertThrows(UnsupportedOperationException.class,
                () -> stub.listTenants(null, null));
    }

    @Test
    void createDraft_stubThrowsUnsupported() {
        assertThrows(UnsupportedOperationException.class,
                () -> stub.createDraft(1L, "SCENE_A", "RULE_001",
                        new RuleContent("规则名", null, null, null, null, null), "actor"));
    }

    @Test
    void getRuleDetail_stubThrowsUnsupported() {
        assertThrows(UnsupportedOperationException.class,
                () -> stub.getRuleDetail(1L, 1L));
    }

    @Test
    void editDraft_stubThrowsUnsupported() {
        assertThrows(UnsupportedOperationException.class,
                () -> stub.editDraft(1L, 1L,
                        new RuleContent("名", "AST_BOOLEAN", null, null, null, null), "actor"));
    }

    @Test
    void newVersion_stubThrowsUnsupported() {
        assertThrows(UnsupportedOperationException.class,
                () -> stub.newVersion(1L, 1L,
                        new RuleContent("名", "AST_BOOLEAN", null, null, null, null), null, "actor"));
    }

    @Test
    void deleteRule_stubThrowsUnsupported() {
        assertThrows(UnsupportedOperationException.class,
                () -> stub.deleteRule(1L, 1L, "actor"));
    }

    @Test
    void deleteDraftVersion_stubThrowsUnsupported() {
        assertThrows(UnsupportedOperationException.class,
                () -> stub.deleteDraftVersion(1L, 1L, 100L, "actor"));
    }
}
