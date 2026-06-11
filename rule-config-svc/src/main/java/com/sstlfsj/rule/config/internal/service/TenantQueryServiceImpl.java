package com.sstlfsj.rule.config.internal.service;

import com.sstlfsj.rule.config.api.service.TenantQueryService;
import com.sstlfsj.rule.config.internal.domain.Tenant;
import com.sstlfsj.rule.config.internal.repository.TenantMapper;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 租户 code→id 解析实现。code→id 是不可变事实（租户 id 永不变），用 {@link ConcurrentHashMap}
 * 永久缓存；不缓存 miss（允许后续新建租户后命中）。租户表极小近静态，边界解析预热后近零成本。
 */
@Service
public class TenantQueryServiceImpl implements TenantQueryService {

    private final TenantMapper mapper;
    private final Map<String, Long> codeToId = new ConcurrentHashMap<>();

    public TenantQueryServiceImpl(TenantMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Long resolveIdByCode(String code) {
        if (code == null || code.isBlank()) return null;
        Long cached = codeToId.get(code);
        if (cached != null) return cached;
        Tenant t = mapper.findByCode(code);
        if (t == null) return null;        // 不缓存 miss
        codeToId.put(code, t.getId());
        return t.getId();
    }
}
