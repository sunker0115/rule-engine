package com.sstlfsj.rule.eval.internal.metric.sql;

import com.sstlfsj.rule.eval.api.service.DatasourceNameService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/** DatasourceNameService 实现：委托 MetricDataSourceRegistry.names()。 */
@Service
@RequiredArgsConstructor
public class DatasourceNameServiceImpl implements DatasourceNameService {

    private final MetricDataSourceRegistry registry;

    @Override
    public Set<String> registeredNames() {
        return registry.names();
    }

    @Override
    public List<String> tables(String datasourceName) {
        var tpl = registry.template(datasourceName);
        if (tpl == null) return List.of();
        return tpl.getJdbcTemplate().queryForList(
                "SELECT TABLE_NAME FROM information_schema.TABLES " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE' " +
                "ORDER BY TABLE_NAME",
                String.class);
    }

    @Override
    public List<String> columns(String datasourceName, String tableName) {
        var tpl = registry.template(datasourceName);
        if (tpl == null) return List.of();
        return tpl.getJdbcTemplate().queryForList(
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? " +
                "ORDER BY ORDINAL_POSITION",
                String.class, tableName);
    }
}
