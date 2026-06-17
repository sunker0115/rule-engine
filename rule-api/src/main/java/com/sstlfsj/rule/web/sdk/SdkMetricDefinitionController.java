package com.sstlfsj.rule.web.sdk;

import com.sstlfsj.rule.config.api.dto.MetricListQuery;
import com.sstlfsj.rule.config.api.service.MetadataService;
import com.sstlfsj.rule.kernel.api.model.MetricDescriptor;
import com.sstlfsj.rule.web.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;

/** SDK metric 定义下发端点：供 MetricDefinitionPoller 拉取定义元数据（不含凭证）。 */
@RestController
@RequestMapping("/sdk/v1")
@RequiredArgsConstructor
public class SdkMetricDefinitionController {

    private final MetadataService metadataService;

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
        // SDK 评估侧入口保持 String tenantId（SPI 不透明标识），在边界处转 Long 喂 config 写链路 Query
        return ApiResponse.ok(metadataService.listMetricDefinitions(
                new MetricListQuery(Long.parseLong(tenantId), sceneList)));
    }
}
