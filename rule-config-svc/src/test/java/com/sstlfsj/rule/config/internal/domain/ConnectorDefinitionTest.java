package com.sstlfsj.rule.config.internal.domain;

import com.baomidou.mybatisplus.annotation.TableField;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** 守护 ConnectorDefinition 实体的列映射注解，防止 @TableField 被误删导致时间字段查不到。 */
class ConnectorDefinitionTest {

    @Test
    void createdAt_hasTableFieldAnnotationWithCorrectColumn() throws NoSuchFieldException {
        Field f = ConnectorDefinition.class.getDeclaredField("createdAt");
        TableField ann = f.getAnnotation(TableField.class);
        assertThat(ann).as("createdAt 必须有 @TableField(\"created_at\")").isNotNull();
        assertThat(ann.value()).isEqualTo("created_at");
        assertThat(f.getType()).isEqualTo(LocalDateTime.class);
    }

    @Test
    void updatedAt_hasTableFieldAnnotationWithCorrectColumn() throws NoSuchFieldException {
        Field f = ConnectorDefinition.class.getDeclaredField("updatedAt");
        TableField ann = f.getAnnotation(TableField.class);
        assertThat(ann).as("updatedAt 必须有 @TableField(\"updated_at\")").isNotNull();
        assertThat(ann.value()).isEqualTo("updated_at");
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
