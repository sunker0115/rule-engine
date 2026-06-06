package com.sstlfsj.rule.web.admin;

import com.sstlfsj.rule.config.api.service.MetadataService;
import com.sstlfsj.rule.web.common.ApiResponse;
import org.springframework.web.bind.annotation.*;

/** 场景元数据查询入口：返回 conditionType / actionType / metric 注册表。 */
@RestController
@RequestMapping("/admin/v1/scenes")
public class MetadataController {

    private final MetadataService metadataService;

    public MetadataController(MetadataService metadataService) {
        this.metadataService = metadataService;
    }

    /** GET /admin/v1/scenes/{sceneCode}/metadata — 查询场景元数据
     * @param sceneCode 场景编码 @param tenantId 租户
     * @return 场景元数据（条件类型、动作类型、指标列表） */
    @GetMapping("/{sceneCode}/metadata")
    public ApiResponse<MetadataService.MetadataResponse> getMetadata(
            @PathVariable String sceneCode,
            @RequestParam String tenantId) {
        return ApiResponse.ok(metadataService.getSceneMetadata(tenantId, sceneCode));
    }

    /** GET /admin/v1/scenes/{sceneCode}/provided-metrics — 发现 §5.2 allowProvided 指标 */
    @GetMapping("/{sceneCode}/provided-metrics")
    public ApiResponse<MetadataService.ProvidedMetricsResponse> getProvidedMetrics(
            @PathVariable String sceneCode,
            @RequestParam String tenantId) {
        return ApiResponse.ok(metadataService.getProvidedMetrics(tenantId, sceneCode));
    }
}
