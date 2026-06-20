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

    @Test
    void tables_delegatesToService() {
        when(svc.tables("ds1")).thenReturn(List.of("orders", "users"));
        ApiResponse<List<String>> resp = controller.tables("ds1");
        assertTrue(resp.success());
        assertEquals(List.of("orders", "users"), resp.data());
    }

    @Test
    void columns_delegatesToService() {
        when(svc.columns("ds1", "orders")).thenReturn(List.of("id", "status", "amount"));
        ApiResponse<List<String>> resp = controller.columns("ds1", "orders");
        assertTrue(resp.success());
        assertEquals(List.of("id", "status", "amount"), resp.data());
    }

    @Test
    void tables_unknownDatasource_returnsEmptyList() {
        when(svc.tables("nonexistent")).thenReturn(List.of());
        ApiResponse<List<String>> resp = controller.tables("nonexistent");
        assertTrue(resp.success());
        assertTrue(resp.data().isEmpty());
    }
}
