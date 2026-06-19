package com.sstlfsj.rule.eval.internal.metric.sql;

import com.sstlfsj.rule.eval.api.service.DatasourceNameService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

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
}
