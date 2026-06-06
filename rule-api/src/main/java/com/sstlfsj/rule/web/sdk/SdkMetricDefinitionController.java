package com.sstlfsj.rule.web.sdk;

import com.sstlfsj.rule.config.api.service.MetadataService;
import com.sstlfsj.rule.kernel.api.model.MetricDescriptor;
import com.sstlfsj.rule.web.common.ApiResponse;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;

/** SDK metric 定义下发端点：供 MetricDefinitionPoller 拉取定义元数据（不含凭证）。 */
@RestController
@RequestMapping("/sdk/v1")
public class SdkMetricDefinitionController {

    private final MetadataService metadataService;

    public SdkMetricDefinitionController(MetadataService metadataService) {
        this.metadataService = metadataService;
    }

    /**
     * 拉取租户的 metric 运行时定义列表。
     *
     * @param tenantId 租户 ID（必填）
     * @param scenes   场景编码列表，逗号分隔；v1 暂不按场景过滤
     * @return MetricDescriptor 列表
     */
    @GetMapping("/metric-definitions")
    public ApiResponse<List<MetricDescriptor>> getMetricDefinitions(
            @RequestParam String tenantId,
            @RequestParam(required = false) String scenes) {
        List<String> sceneList = (scenes == null || scenes.isBlank())
                ? List.of()
                : Arrays.stream(scenes.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        return ApiResponse.ok(metadataService.listMetricDefinitions(tenantId, sceneList));
    }
}
