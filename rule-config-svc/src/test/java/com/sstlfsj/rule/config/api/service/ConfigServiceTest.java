package com.sstlfsj.rule.config.api.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sstlfsj.rule.config.api.dto.RuleListItemVO;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import org.junit.jupiter.api.Test;

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
}
