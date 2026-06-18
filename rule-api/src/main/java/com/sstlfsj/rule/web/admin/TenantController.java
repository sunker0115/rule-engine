package com.sstlfsj.rule.web.admin;

import com.sstlfsj.rule.config.api.dto.TenantItemVO;
import com.sstlfsj.rule.config.api.service.ConfigService;
import com.sstlfsj.rule.web.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 租户管理入口：列表查询、启/禁用。 */
@RestController
@RequestMapping("/admin/v1/tenants")
@RequiredArgsConstructor
public class TenantController {

    private final ConfigService configService;

    @GetMapping
    public ApiResponse<List<TenantItemVO>> listTenants(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(configService.listTenants(keyword, status));
    }

    @PutMapping("/{id}/status")
    public ApiResponse<Void> toggleStatus(
            @PathVariable Long id,
            @RequestParam boolean enable) {
        configService.toggleTenantStatus(id, enable);
        return ApiResponse.ok(null);
    }
}
