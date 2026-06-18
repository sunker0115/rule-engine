package com.sstlfsj.rule.web.admin;

import com.sstlfsj.rule.config.api.dto.MetricListItemVO;
import com.sstlfsj.rule.config.api.service.MetadataService;
import com.sstlfsj.rule.config.api.service.MetricWriteService;
import com.sstlfsj.rule.config.api.service.MetricWriteService.MetricWriteCommand;
import com.sstlfsj.rule.config.api.service.MetricWriteService.RuleRef;
import com.sstlfsj.rule.eval.api.FetchTrace;
import com.sstlfsj.rule.eval.api.service.MetricFetchTestService;
import com.sstlfsj.rule.web.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Metric 注册 / 更新 / 影响面查询入口（10-api-contract §3 /admin/v1/metrics）。 */
@RestController
@RequestMapping("/admin/v1/metrics")
@RequiredArgsConstructor
public class MetricController {

    private final MetricWriteService service;
    private final MetadataService metadataService;
    private final MetricFetchTestService testService;

    /**
     * GET /admin/v1/metrics — 查询租户全部 metric 运行时定义。
     *
     * @param tenantId 租户 ID
     * @return metric 定义列表
     */
    @GetMapping
    public ApiResponse<List<MetricListItemVO>> listMetrics(@RequestParam Long tenantId) {
        return ApiResponse.ok(metadataService.listMetricItems(tenantId));
    }

    /** GET /admin/v1/metrics/usage-counts — tenant 下每个 metric 的被引用计数（列表徽标，版本无关）。 */
    @GetMapping("/usage-counts")
    public ApiResponse<List<com.sstlfsj.rule.config.api.service.UsageCount>> usageCounts(@RequestParam Long tenantId) {
        return ApiResponse.ok(service.countRuleUsages(tenantId));
    }

    /**
     * GET /admin/v1/metrics/{metricCode} — 查单个 metric 完整定义，供前端编辑器加载。
     *
     * @param metricCode metric 编码
     * @param tenantId   租户 ID
     * @return metric 完整定义
     */
    @GetMapping("/{metricCode}")
    public ApiResponse<MetricListItemVO> getMetric(@PathVariable String metricCode,
                                                   @RequestParam Long tenantId) {
        return ApiResponse.ok(metadataService.getMetricItem(tenantId, metricCode));
    }

    /**
     * POST /admin/v1/metrics — 注册新 metric（version=1, status=ACTIVE）。
     *
     * @param tenantId   租户 ID
     * @param metricCode metric 编码，作为 query param 传入
     * @param actorId    操作人
     * @param cmd        写入参数
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
     * PUT /admin/v1/metrics/{metricCode} — 更新 metric；breakingChange=true 触发升版。
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
     * GET /admin/v1/metrics/{metricCode}/versions/{version}/impact — 查询引用该版本的 ACTIVE 规则清单。
     *
     * @param metricCode metric 编码
     * @param version    metric 版本号
     * @param tenantId   租户 ID
     * @return 影响面响应，含 metricCode/metricVersion/affectedRules/affectedRuleCount
     */
    @GetMapping("/{metricCode}/versions/{version}/impact")
    public ApiResponse<ImpactResponse> impact(@PathVariable String metricCode,
                                              @PathVariable int version,
                                              @RequestParam Long tenantId) {
        List<RuleRef> rules = service.findReferencingRules(tenantId, metricCode, version);
        return ApiResponse.ok(new ImpactResponse(metricCode, version, rules, rules.size()));
    }

    /** 影响面查询响应：被某 metric 版本影响的规则清单（10-api-contract §4.7）。 */
    public record ImpactResponse(String metricCode, int metricVersion,
                                 List<RuleRef> affectedRules, int affectedRuleCount) {}

    /**
     * PUT /admin/v1/metrics/{metricCode}/status — 启/禁 metric。
     *
     * @param metricCode metric 编码
     * @param tenantId   租户 ID
     * @param enable     true 启用 / false 禁用
     * @return 操作后的状态
     */
    @PutMapping("/{metricCode}/status")
    public ApiResponse<Map<String, String>> toggleStatus(
            @PathVariable String metricCode,
            @RequestParam Long tenantId,
            @RequestParam boolean enable) {
        metadataService.toggleMetricStatus(tenantId, metricCode, enable);
        return ApiResponse.ok(Map.of("status", enable ? "ACTIVE" : "DISABLED"));
    }

    /**
     * POST /admin/v1/metrics/{metricCode}:test — 用样例输入实打实取数一次，返回分阶段 trace。
     *
     * @param metricCode metric 编码
     * @param tenantId   租户 ID
     * @param req        样例入参（vars / payload / subjectId）
     * @return 分阶段取数 trace
     */
    @PostMapping("/{metricCode}:test")
    public ApiResponse<FetchTrace> test(@PathVariable String metricCode,
                                        @RequestParam Long tenantId,
                                        @RequestBody TestRequest req) {
        return ApiResponse.ok(testService.test(tenantId, metricCode,
                req.sampleVars(), req.samplePayload(), req.sampleSubjectId()));
    }

    /** 自助测试样例入参（异构样本，Map 合规例外）。 */
    public record TestRequest(Map<String, Object> sampleVars, Map<String, Object> samplePayload,
                              String sampleSubjectId) {}
}
