package com.sstlfsj.rule.config.internal.service;

import com.sstlfsj.rule.config.internal.domain.Tenant;
import com.sstlfsj.rule.config.internal.repository.TenantMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TenantQueryServiceImplTest {

    @Mock TenantMapper mapper;

    private static Tenant tenant(long id, String code) {
        Tenant t = new Tenant();
        t.setId(id);
        t.setCode(code);
        t.setStatus("ACTIVE");
        return t;
    }

    @Test
    void resolveIdByCode_returnsId() {
        when(mapper.findByCode("acme")).thenReturn(tenant(9001L, "acme"));
        TenantQueryServiceImpl svc = new TenantQueryServiceImpl(mapper);

        assertThat(svc.resolveIdByCode("acme")).isEqualTo(9001L);
    }

    @Test
    void resolveIdByCode_secondCall_servedFromCache() {
        when(mapper.findByCode("acme")).thenReturn(tenant(9001L, "acme"));
        TenantQueryServiceImpl svc = new TenantQueryServiceImpl(mapper);

        svc.resolveIdByCode("acme");
        svc.resolveIdByCode("acme");

        verify(mapper, times(1)).findByCode("acme");   // 不可变映射，仅查库一次
    }

    @Test
    void resolveIdByCode_unknownCode_returnsNull_notCached() {
        when(mapper.findByCode("ghost")).thenReturn(null);
        TenantQueryServiceImpl svc = new TenantQueryServiceImpl(mapper);

        assertThat(svc.resolveIdByCode("ghost")).isNull();
        assertThat(svc.resolveIdByCode("ghost")).isNull();

        verify(mapper, times(2)).findByCode("ghost");   // miss 不缓存，允许后续新建后命中
    }

    @Test
    void resolveIdByCode_nullOrBlank_returnsNull_noDbHit() {
        TenantQueryServiceImpl svc = new TenantQueryServiceImpl(mapper);

        assertThat(svc.resolveIdByCode(null)).isNull();
        assertThat(svc.resolveIdByCode("  ")).isNull();

        verifyNoInteractions(mapper);
    }
}
