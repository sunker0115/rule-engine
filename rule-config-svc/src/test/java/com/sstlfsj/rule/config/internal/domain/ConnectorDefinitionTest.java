package com.sstlfsj.rule.config.internal.domain;

import com.baomidou.mybatisplus.annotation.TableField;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守护 ConnectorDefinition 实体时间字段——autoResultMap=true + map-underscore-to-camel-case 自动映射
 * created_at→createdAt / updated_at→updatedAt（与 MetricDefinition 同款，不需要显式 @TableField）。
 */
class ConnectorDefinitionTest {

    @Test
    void createdAt_fieldExistsAndIsLocalDateTime() throws NoSuchFieldException {
        Field f = ConnectorDefinition.class.getDeclaredField("createdAt");
        assertThat(f.getType()).isEqualTo(LocalDateTime.class);
        // 不加 @TableField：autoResultMap + underscore-to-camel 自动映射 created_at→createdAt
        assertThat(f.getAnnotation(TableField.class)).isNull();
    }

    @Test
    void updatedAt_fieldExistsAndIsLocalDateTime() throws NoSuchFieldException {
        Field f = ConnectorDefinition.class.getDeclaredField("updatedAt");
        assertThat(f.getType()).isEqualTo(LocalDateTime.class);
        assertThat(f.getAnnotation(TableField.class)).isNull();
    }

    @Test
    void settersGetters_workForTimestamps() {
        ConnectorDefinition d = new ConnectorDefinition();
        LocalDateTime now = LocalDateTime.of(2026, 6, 16, 12, 0);
        d.setCreatedAt(now);
        d.setUpdatedAt(now);
        assertThat(d.getCreatedAt()).isEqualTo(now);
        assertThat(d.getUpdatedAt()).isEqualTo(now);
    }
}
