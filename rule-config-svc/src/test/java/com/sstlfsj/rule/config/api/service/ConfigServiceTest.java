package com.sstlfsj.rule.config.api.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sstlfsj.rule.config.api.dto.DraftCreatedResult;
import com.sstlfsj.rule.config.api.dto.RuleDetailVO;
import com.sstlfsj.rule.config.internal.domain.RuleDefinition;
import com.sstlfsj.rule.config.api.dto.TenantItemVO;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.DecisionBinding;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.PreGateConfig;
import com.sstlfsj.rule.kernel.api.model.ScriptSource;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证 ConfigService 契约：方法签名可编译，Stub 实现能正确抛出 UnsupportedOperationException。 */
class ConfigServiceTest {

    private final ConfigService stub = new ConfigService() {
        @Override
        public RuleVersionSnapshot publish(String tenantId, Long ruleDefinitionId, String actorId) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public void disable(String tenantId, Long ruleDefinitionId, String actorId) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public Page<RuleDefinition> listRules(String tenantId, String sceneCode, String status,
                String from, String to, int page, int size) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public DraftCreatedResult createDraft(String tenantId, String sceneCode,
                String code, String name,
                AstNode conditionAst, List<DecisionBinding> decisionBindings,
                List<PreGateConfig> preGates, List<String> triggerEventTypes,
                String kind, ScriptSource script, String actorId) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public RuleDetailVO getRuleDetail(String tenantId, Long ruleId) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public DraftCreatedResult editDraft(String tenantId, Long ruleId, String name, String kind,
                AstNode conditionAst, List<DecisionBinding> decisionBindings,
                List<PreGateConfig> preGates, List<String> triggerEventTypes,
                ScriptSource script, String actorId) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public DraftCreatedResult newVersion(String tenantId, Long ruleId, String name, String kind,
                AstNode conditionAst, List<DecisionBinding> decisionBindings,
                List<PreGateConfig> preGates, List<String> triggerEventTypes,
                Long fromVersionId, ScriptSource script, String actorId) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public void deleteRule(String tenantId, Long ruleId, String actorId) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public void deleteDraftVersion(String tenantId, Long ruleId, Long versionId, String actorId) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public List<TenantItemVO> listTenants(String keyword, String status) {
            throw new UnsupportedOperationException("stub");
        }
        @Override public Map<Long, String> getSceneCodeMap(Set<Long> sceneIds) { throw new UnsupportedOperationException("stub"); }
        @Override public void toggleTenantStatus(Long tenantId, boolean enable) { throw new UnsupportedOperationException("stub"); }
    };

    @Test
    void publish_stubThrowsUnsupported() {
        assertThrows(UnsupportedOperationException.class,
                () -> stub.publish("t1", 1L, "actor"));
    }

    @Test
    void disable_stubThrowsUnsupported() {
        assertThrows(UnsupportedOperationException.class,
                () -> stub.disable("t1", 1L, "actor"));
    }

    @Test
    void listRules_stubThrowsUnsupported() {
        assertThrows(UnsupportedOperationException.class,
                () -> stub.listRules("t1", null, null, null, null, 1, 20));
    }

    @Test
    void listTenants_stubThrowsUnsupported() {
        assertThrows(UnsupportedOperationException.class,
                () -> stub.listTenants(null, null));
    }

    @Test
    void createDraft_stubThrowsUnsupported() {
        assertThrows(UnsupportedOperationException.class,
                () -> stub.createDraft("t1", "SCENE_A", "RULE_001", "规则名",
                        null, null, null, null, null, null, "actor"));
    }

    @Test
    void getRuleDetail_stubThrowsUnsupported() {
        assertThrows(UnsupportedOperationException.class,
                () -> stub.getRuleDetail("t1", 1L));
    }

    @Test
    void editDraft_stubThrowsUnsupported() {
        assertThrows(UnsupportedOperationException.class,
                () -> stub.editDraft("t1", 1L, "名", "AST_BOOLEAN",
                        null, null, null, null, null, "actor"));
    }

    @Test
    void newVersion_stubThrowsUnsupported() {
        assertThrows(UnsupportedOperationException.class,
                () -> stub.newVersion("t1", 1L, "名", "AST_BOOLEAN",
                        null, null, null, null, null, null, "actor"));
    }

    @Test
    void deleteRule_stubThrowsUnsupported() {
        assertThrows(UnsupportedOperationException.class,
                () -> stub.deleteRule("t1", 1L, "actor"));
    }

    @Test
    void deleteDraftVersion_stubThrowsUnsupported() {
        assertThrows(UnsupportedOperationException.class,
                () -> stub.deleteDraftVersion("t1", 1L, 100L, "actor"));
    }
}
