package com.sstlfsj.rule.eval.internal.metric.sql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 命名只读 DataSource 注册表：按配置建只读 Hikari 连接池，封 NamedParameterJdbcTemplate。
 * metric 只能引用已注册的逻辑名（杜绝误写主库、灭 SSRF）。
 */
@Component
public class MetricDataSourceRegistry implements AutoCloseable {

    private final Map<String, NamedParameterJdbcTemplate> templates = new HashMap<>();
    private final Map<String, HikariDataSource> pools = new HashMap<>();

    public MetricDataSourceRegistry(FetchResourceProperties props) {
        for (FetchResourceProperties.DataSourceDef def : props.getDatasources()) {
            HikariConfig cfg = new HikariConfig();
            cfg.setJdbcUrl(def.getUrl());
            cfg.setUsername(def.getUsername());
            cfg.setPassword(def.getPassword());
            cfg.setReadOnly(true);                 // 只读：拒绝写操作，卸载主库
            cfg.setMaximumPoolSize(8);
            cfg.setPoolName("metric-ro-" + def.getName());
            HikariDataSource ds = new HikariDataSource(cfg);
            pools.put(def.getName(), ds);
            templates.put(def.getName(), new NamedParameterJdbcTemplate(ds));
        }
    }

    /**
     * 取命名数据源的 NamedParameterJdbcTemplate。
     *
     * @param name 逻辑数据源名
     * @return template；未注册返回 null
     */
    public NamedParameterJdbcTemplate template(String name) {
        return templates.get(name);
    }

    /** @return 是否已注册该名字。 */
    public boolean isRegistered(String name) {
        return templates.containsKey(name);
    }

    /** @return 所有已注册的数据源名（供发布期资源名校验）。 */
    public Set<String> names() {
        return Set.copyOf(templates.keySet());
    }

    @Override
    public void close() {
        pools.values().forEach(HikariDataSource::close);
    }
}
