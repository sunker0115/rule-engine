package com.sstlfsj.rule.config.internal.expression;

import com.sstlfsj.rule.config.api.dto.PayloadFieldSpec;
import com.sstlfsj.rule.config.api.dto.PayloadFieldType;
import com.sstlfsj.rule.config.internal.domain.MetricDefinition;
import com.sstlfsj.rule.config.internal.domain.SceneDef;
import com.sstlfsj.rule.config.internal.repository.MetricDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.SceneMapper;
import com.sstlfsj.rule.kernel.api.model.DataType;
import com.sstlfsj.rule.kernel.api.spi.expression.ExpressionCompileException;
import com.sstlfsj.rule.kernel.api.spi.expression.ExpressionEngine;
import com.sstlfsj.rule.kernel.api.spi.expression.ScriptTypeEnv;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 表达式实时类型诊断：复用发布期 {@link ExpressionEngine#typeCheck}，只 check 不 eval、不 persist。
 * 数据源：scene 的 payloadSchema + 租户下所有 ACTIVE metric 的 dataType。
 *
 * <p>弱类型引擎（Aviator/Groovy/JEXL 等）的 typeCheck 为 no-op，返回 valid=true。
 */
@Service
public class ExpressionValidationService {

    private final Map<String, ExpressionEngine> engines;
    private final SceneMapper sceneMapper;
    private final MetricDefinitionMapper metricMapper;

    public ExpressionValidationService(List<ExpressionEngine> expressionEngines,
                                        SceneMapper sceneMapper,
                                        MetricDefinitionMapper metricMapper) {
        Map<String, ExpressionEngine> byLang = new HashMap<>();
        if (expressionEngines != null) {
            for (ExpressionEngine e : expressionEngines) {
                if (byLang.putIfAbsent(e.lang(), e) != null) {
                    throw new IllegalStateException("多个 ExpressionEngine 声明同一 lang=" + e.lang());
                }
            }
        }
        this.engines = byLang;
        this.sceneMapper = sceneMapper;
        this.metricMapper = metricMapper;
    }

    /**
     * 实时校验表达式语法与类型（只 check 不 eval）。
     *
     * @return 错误信息；null 表示通过
     * @throws IllegalArgumentException lang 无对应引擎 / scene 不存在
     */
    public String validate(Long tenantId, String sceneCode, String lang, String source) {
        ExpressionEngine engine = engines.get(lang);
        if (engine == null) {
            throw new IllegalArgumentException("无对应表达式引擎,lang=" + lang);
        }

        SceneDef scene = sceneMapper.findByCode(tenantId, sceneCode);
        if (scene == null) {
            throw new IllegalArgumentException("Scene 不存在: tenantId=" + tenantId + ", sceneCode=" + sceneCode);
        }

        ScriptTypeEnv typeEnv = buildTypeEnv(scene, metricMapper.findActiveByTenant(tenantId));
        try {
            engine.typeCheck(source, typeEnv);
            return null;
        } catch (ExpressionCompileException e) {
            return e.getMessage();
        }
    }

    /**
     * 从 scene payloadSchema + tenant aware 的 ACTIVE metric 定义构造类型环境。
     * payload schemaType → kernel DataType 映射内联（等价 PayloadDataTypeMapper，同模块下复用）。
     */
    private static ScriptTypeEnv buildTypeEnv(SceneDef scene, List<MetricDefinition> metrics) {
        Map<String, DataType> payloadTypes = new HashMap<>();
        List<PayloadFieldSpec> schema = scene.getPayloadSchema();
        if (schema != null) {
            for (PayloadFieldSpec f : schema) {
                String dtTag = toDataTypeTag(f.type());
                payloadTypes.put(f.name(), DataType.fromTag(dtTag));
            }
        }

        Map<String, DataType> metricTypes = new HashMap<>();
        for (MetricDefinition m : metrics) {
            String dataType = m.getDataType();
            if (dataType != null) {
                metricTypes.put(m.getMetricCode(), DataType.fromTag(dataType));
            }
        }

        return new ScriptTypeEnv(metricTypes, payloadTypes);
    }

    /** payloadSchema 字段类型 → kernel DataType 标签，映射同 PayloadDataTypeMapper。 */
    private static String toDataTypeTag(String schemaType) {
        return switch (PayloadFieldType.fromTag(schemaType)) {
            case NUMBER  -> DataType.DECIMAL.tag();
            case INTEGER -> DataType.LONG.tag();
            case STRING  -> DataType.STRING.tag();
            case BOOLEAN -> DataType.BOOLEAN.tag();
            case ARRAY   -> DataType.LIST.tag();
            case OBJECT  -> DataType.UNKNOWN.tag();
        };
    }

}
