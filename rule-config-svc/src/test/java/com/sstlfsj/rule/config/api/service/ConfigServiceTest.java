package com.sstlfsj.rule.config.api.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sstlfsj.rule.config.api.dto.DraftCreatedResult;
import com.sstlfsj.rule.config.api.dto.RuleDetailVO;
import com.sstlfsj.rule.config.api.dto.RuleListItemVO;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.DecisionBinding;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.PreGateConfig;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;
import org.junit.jupiter.api.Test;

import java.util.List;

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
        public Page<RuleListItemVO> listRules(String tenantId, String sceneCode, String status, int page, int size) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public DraftCreatedResult createDraft(String tenantId, String sceneCode,
                String code, String name,
                AstNode conditionAst, List<DecisionBinding> decisionBindings,
                List<PreGateConfig> preGates, List<String> triggerEventTypes,
                String kind, String actorId) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public RuleDetailVO getRuleDetail(String tenantId, Long ruleId) {
            throw new UnsupportedOperationException("stub");
        }
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
                () -> stub.listRules("t1", null, null, 1, 20));
    }

    @Test
    void createDraft_stubThrowsUnsupported() {
        assertThrows(UnsupportedOperationException.class,
                () -> stub.createDraft("t1", "SCENE_A", "RULE_001", "规则名",
                        null, null, null, null, null, "actor"));
    }

    @Test
    void getRuleDetail_stubThrowsUnsupported() {
        assertThrows(UnsupportedOperationException.class,
                () -> stub.getRuleDetail("t1", 1L));
    }
}
