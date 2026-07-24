package com.sstlfsj.rule.web.admin;

import com.sstlfsj.rule.config.api.service.MetadataService;
import com.sstlfsj.rule.web.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** 场景元数据查询入口：返回 conditionType / metric 注册表。 */
@RestController
@RequestMapping("/admin/v1/scenes")
@RequiredArgsConstructor
public class MetadataController {

    private final MetadataService metadataService;

    /** GET /admin/v1/scenes/{sceneCode}/metadata — 查询场景元数据
     * @param sceneCode 场景编码 @param tenantId 租户
     * @return 场景元数据（条件类型、指标列表） */
    @GetMapping("/{sceneCode}/metadata")
    public ApiResponse<MetadataService.MetadataResponse> getMetadata(
            @PathVariable String sceneCode,
            @RequestParam Long tenantId) {
        return ApiResponse.ok(metadataService.getSceneMetadata(tenantId, sceneCode));
    }
}

