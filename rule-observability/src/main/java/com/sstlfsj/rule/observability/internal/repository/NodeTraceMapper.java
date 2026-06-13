package com.sstlfsj.rule.observability.internal.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.observability.internal.domain.NodeTraceEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;
import java.util.List;

/** node_trace 表 MyBatis-Plus Mapper（批量写，异步通道）。 */
@Mapper
public interface NodeTraceMapper extends BaseMapper<NodeTraceEntity> {

    /**
     * 批量插入 node_trace 行，生成单条多值 INSERT 语句，减少数据库往返。
     * 列表为空时不执行（由调用方保证）。
     */
    @Insert("""
            <script>
            INSERT INTO node_trace
              (evaluation_session_id, tenant_id, rule_version_id, rule_code, rule_version, node_path, node_type,
               condition_type, metric_code, display_label, params, actual_value, result,
               error_code, value_source, evaluated_at)
            VALUES
            <foreach collection="list" item="e" separator=",">
              (#{e.evaluationSessionId}, #{e.tenantId}, #{e.ruleVersionId}, #{e.ruleCode}, #{e.ruleVersion}, #{e.nodePath}, #{e.nodeType},
               #{e.conditionType}, #{e.metricCode}, #{e.displayLabel}, #{e.params}, #{e.actualValue}, #{e.result},
               #{e.errorCode}, #{e.valueSource}, #{e.evaluatedAt})
            </foreach>
            </script>
            """)
    void insertBatch(List<NodeTraceEntity> list);

    /** 删 evaluated_at 早于 cutoff 的行，单次最多 batchSize 条（分批短事务）。返回删除行数。 */
    default int purgeOlderThan(LocalDateTime cutoff, int batchSize) {
        // batchSize 为常量 int，无注入风险
        return delete(new LambdaQueryWrapper<NodeTraceEntity>()
                .lt(NodeTraceEntity::getEvaluatedAt, cutoff)
                .last("LIMIT " + batchSize));
    }
}
