package com.sstlfsj.rule.audit.internal.repository;

import com.sstlfsj.rule.audit.internal.domain.ConfusionCountRow;
import com.sstlfsj.rule.audit.internal.domain.WindowTotalsRow;
import org.apache.ibatis.annotations.Param;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 EffectivenessReadMapper 方法签名 + @Param 命名与 XML 占位符对齐（防漂移）。
 * JSON_TABLE 聚合 SQL 的真实落库行为在 e2e 验证（需 MySQL 8 方言）。
 */
class EffectivenessReadMapperTest {

    @Test
    void confusionByDimension_signatureAndParams() throws Exception {
        Method m = EffectivenessReadMapper.class.getMethod("confusionByDimension",
                Long.class, String.class, LocalDateTime.class, LocalDateTime.class,
                String.class, List.class, String.class);
        ParameterizedType ret = (ParameterizedType) m.getGenericReturnType();
        assertThat(ret.getActualTypeArguments()[0]).isEqualTo(ConfusionCountRow.class);
        assertThat(paramNames(m)).containsExactly(
                "tenantId", "sceneCode", "from", "to", "dimension", "positiveLabels", "bucketUnit");
    }

    @Test
    void windowTotals_signatureAndParams() throws Exception {
        Method m = EffectivenessReadMapper.class.getMethod("windowTotals",
                Long.class, String.class, LocalDateTime.class, LocalDateTime.class,
                List.class, String.class);
        ParameterizedType ret = (ParameterizedType) m.getGenericReturnType();
        assertThat(ret.getActualTypeArguments()[0]).isEqualTo(WindowTotalsRow.class);
        assertThat(paramNames(m)).containsExactly(
                "tenantId", "sceneCode", "from", "to", "positiveLabels", "bucketUnit");
    }

    private static List<String> paramNames(Method m) {
        java.lang.annotation.Annotation[][] anns = m.getParameterAnnotations();
        return java.util.Arrays.stream(anns)
                .map(a -> ((Param) a[0]).value())
                .toList();
    }
}
