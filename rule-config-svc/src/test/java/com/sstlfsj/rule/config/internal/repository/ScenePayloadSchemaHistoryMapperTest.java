package com.sstlfsj.rule.config.internal.repository;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ScenePayloadSchemaHistoryMapper 编译与接口存在性验证。
 * 真实数据库集成测试在 Task 3（updateScene 快照写入）中通过 Testcontainers 覆盖。
 */
class ScenePayloadSchemaHistoryMapperTest {

    @Test
    void mapperInterface_isLoadable() {
        // 验证接口可被 Class.forName 加载，确保包路径、注解无编译错误
        Class<?> clazz = ScenePayloadSchemaHistoryMapper.class;
        assertThat(clazz).isInterface();
        assertThat(clazz.getSimpleName()).isEqualTo("ScenePayloadSchemaHistoryMapper");
    }
}
