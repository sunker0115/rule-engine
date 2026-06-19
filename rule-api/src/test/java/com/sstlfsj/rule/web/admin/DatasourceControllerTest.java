package com.sstlfsj.rule.web.admin;

import com.sstlfsj.rule.eval.api.service.DatasourceNameService;
import com.sstlfsj.rule.web.common.ApiResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DatasourceControllerTest {

    private final DatasourceNameService svc = mock(DatasourceNameService.class);
    private final DatasourceController controller = new DatasourceController(svc);

    @Test
    void list_returnsSortedNames() {
        when(svc.registeredNames()).thenReturn(Set.of("biz", "analytics", "fraud"));
        ApiResponse<List<String>> resp = controller.list();
        assertTrue(resp.success());
        assertEquals(List.of("analytics", "biz", "fraud"), resp.data());
    }

    @Test
    void list_emptyRegistry_returnsEmptyList() {
        when(svc.registeredNames()).thenReturn(Set.of());
        ApiResponse<List<String>> resp = controller.list();
        assertTrue(resp.success());
        assertTrue(resp.data().isEmpty());
    }
}
