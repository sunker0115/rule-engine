package com.sstlfsj.rule.web.admin;

import com.sstlfsj.rule.config.api.dto.TenantItemVO;
import com.sstlfsj.rule.config.api.service.ConfigService;
import com.sstlfsj.rule.web.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 租户管理查询入口。 */
@RestController
@RequestMapping("/admin/v1/tenants")
@RequiredArgsConstructor
public class TenantController {

    private final ConfigService configService;

    @GetMapping
    public ApiResponse<List<TenantItemVO>> listTenants() {
        return ApiResponse.ok(configService.listTenants());
    }
}
