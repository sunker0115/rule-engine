package com.sstlfsj.rule.web.api;

import com.sstlfsj.rule.config.api.service.MetadataService;
import com.sstlfsj.rule.config.api.service.MetadataService.InputManifestResponse;
import com.sstlfsj.rule.config.api.service.TenantQueryService;
import com.sstlfsj.rule.web.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 公开发现接口：查场景的输入参数清单（调用方据此精确传 payload）。 */
@RestController
@RequestMapping("/api/v1/rule/scenes")
@RequiredArgsConstructor
public class SceneManifestController {

    private final MetadataService metadataService;
    private final TenantQueryService tenantQueryService;

    /**
     * GET /api/v1/rule/scenes/{sceneCode}/input-manifest?tenantCode=xxx[&eventType=xxx]
     * — 查该场景所有 ACTIVE 规则引用的 payload 字段并集。
     *
     * @param sceneCode  场景编码
     * @param tenantCode 租户业务标识（边界解析为内部 id，surrogate 不外泄）
     * @param eventType  事件类型；非空时收窄到会被该事件触发的规则，缺省不收窄
     * @return 输入字段清单（按 name 去重）
     */
    @GetMapping("/{sceneCode}/input-manifest")
    public ApiResponse<InputManifestResponse> inputManifest(
            @PathVariable String sceneCode,
            @RequestParam String tenantCode,
            @RequestParam(required = false) String eventType) {
        Long tenantId = tenantQueryService.resolveIdByCode(tenantCode);
        if (tenantId == null) {
            throw new IllegalArgumentException("未知或缺失的租户 code: " + tenantCode);
        }
        return ApiResponse.ok(
                metadataService.getInputManifest(String.valueOf(tenantId), sceneCode, eventType));
    }
}
