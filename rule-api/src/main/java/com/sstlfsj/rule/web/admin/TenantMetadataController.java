package com.sstlfsj.rule.web.admin;

import com.sstlfsj.rule.config.api.service.MetadataService;
import com.sstlfsj.rule.web.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 租户级元数据查询——不依赖 scene，供模板编辑器等 scene-agnostic 上下文使用。
 * conditionTypes(SPI 全局) + tenant 全量 ACTIVE metrics + expressionLangs。
 */
@RestController
@RequestMapping("/admin/v1/metadata")
@RequiredArgsConstructor
public class TenantMetadataController {

    private final MetadataService metadataService;

    /** GET /admin/v1/metadata?tenantId=X — 返回租户级元数据（无需 scene）。 */
    @GetMapping
    public ApiResponse<MetadataService.MetadataResponse> getTenantMetadata(
            @RequestParam Long tenantId) {
        return ApiResponse.ok(metadataService.getTenantMetadata(tenantId));
    }
}
