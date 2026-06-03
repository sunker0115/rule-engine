package com.sstlfsj.rule.observability.internal.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.observability.internal.domain.DryRunNodeTraceEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/** dry_run_node_trace 表 MyBatis-Plus Mapper（批量写，异步通道）。 */
@Mapper
public interface DryRunNodeTraceMapper extends BaseMapper<DryRunNodeTraceEntity> {

    /**
     * 批量插入 dry_run_node_trace 行，生成单条多值 INSERT 语句。
     * 列表为空时不执行（由调用方保证）。
     */
    @Insert("""
            <script>
            INSERT INTO dry_run_node_trace
              (dry_run_session_id, tenant_id, rule_version_id, node_path, node_type,
               condition_type, metric_code, actual_value, result,
               error_code, value_source, evaluated_at)
            VALUES
            <foreach collection="list" item="e" separator=",">
              (#{e.dryRunSessionId}, #{e.tenantId}, #{e.ruleVersionId}, #{e.nodePath}, #{e.nodeType},
               #{e.conditionType}, #{e.metricCode}, #{e.actualValue}, #{e.result},
               #{e.errorCode}, #{e.valueSource}, #{e.evaluatedAt})
            </foreach>
            </script>
            """)
    void insertBatch(List<DryRunNodeTraceEntity> list);
}
