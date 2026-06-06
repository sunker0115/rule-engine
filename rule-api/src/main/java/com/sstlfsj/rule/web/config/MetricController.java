package com.sstlfsj.rule.web.config;

import com.sstlfsj.rule.config.api.service.MetricWriteService;
import com.sstlfsj.rule.config.api.service.MetricWriteService.MetricWriteCommand;
import com.sstlfsj.rule.config.api.service.MetricWriteService.RuleRef;
import com.sstlfsj.rule.web.common.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Metric 注册 / 更新 / 影响面查询入口（10-api-contract §3 /api/v1/metrics）。 */
@RestController
@RequestMapping("/api/v1/metrics")
public class MetricController {

    private final MetricWriteService service;

    public MetricController(MetricWriteService service) {
        this.service = service;
    }

    /**
     * POST /api/v1/metrics — 注册新 metric（version=1, status=ACTIVE）。
     *
     * @param tenantId  租户 ID
     * @param actorId   操作人
     * @param metricCode metric 编码（路径占位符不适用 create，用 body 字段或 param 均可；此处取 metricCode 作为独立 param）
     * @param cmd       写入参数
     * @return 新建行的 id
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Long> create(@RequestParam Long tenantId,
                                    @RequestParam String metricCode,
                                    @RequestHeader("X-Actor-Id") String actorId,
                                    @RequestBody MetricWriteCommand cmd) {
        return ApiResponse.ok(service.create(tenantId, metricCode, cmd, actorId));
    }

    /**
     * PUT /api/v1/metrics/{metricCode} — 更新 metric；breakingChange=true 触发升版。
     *
     * @param metricCode    metric 编码
     * @param tenantId      租户 ID
     * @param breakingChange 是否语义不兼容升版（默认 false）
     * @param actorId       操作人
     * @param cmd           写入参数
     * @return 当前生效行的 version
     */
    @PutMapping("/{metricCode}")
    public ApiResponse<Integer> update(@PathVariable String metricCode,
                                       @RequestParam Long tenantId,
                                       @RequestParam(defaultValue = "false") boolean breakingChange,
                                       @RequestHeader("X-Actor-Id") String actorId,
                                       @RequestBody MetricWriteCommand cmd) {
        return ApiResponse.ok(service.update(tenantId, metricCode, cmd, breakingChange, actorId));
    }

    /**
     * GET /api/v1/metrics/{metricCode}/versions/{version}/impact — 查询引用该版本的 ACTIVE 规则清单。
     *
     * @param metricCode metric 编码
     * @param version    metric 版本号
     * @param tenantId   租户 ID
     * @return 引用该版本的规则列表
     */
    @GetMapping("/{metricCode}/versions/{version}/impact")
    public ApiResponse<List<RuleRef>> impact(@PathVariable String metricCode,
                                             @PathVariable int version,
                                             @RequestParam Long tenantId) {
        return ApiResponse.ok(service.findReferencingRules(tenantId, metricCode, version));
    }
}
