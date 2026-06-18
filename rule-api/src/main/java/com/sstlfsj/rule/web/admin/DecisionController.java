package com.sstlfsj.rule.web.admin;

import com.sstlfsj.rule.config.api.service.DecisionService;
import com.sstlfsj.rule.config.api.service.UsageCount;
import com.sstlfsj.rule.config.internal.domain.DecisionDefinition;
import com.sstlfsj.rule.web.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Decision tenant 级写 API（D26/D27：Decision 与 scene 无关，CRUD）。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/v1/decisions")
public class DecisionController {

    private final DecisionService decisionService;

    /** POST /admin/v1/decisions?tenantId=xxx — 新建 decision。 */
    @PostMapping
    public ApiResponse<Long> create(@RequestParam Long tenantId,
                                    @RequestHeader("X-Actor-Id") String actorId,
                                    @RequestBody DecisionRequest req) {
        return ApiResponse.ok(decisionService.create(tenantId, req.code(), req.name(),
                req.priority(), req.description(), actorId));
    }

    /** PUT /admin/v1/decisions/{code}?tenantId=xxx — 更新 decision。 */
    @PutMapping("/{code}")
    public ApiResponse<Void> update(@PathVariable String code,
                                    @RequestParam Long tenantId,
                                    @RequestHeader("X-Actor-Id") String actorId,
                                    @RequestBody DecisionRequest req) {
        decisionService.update(tenantId, code, req.name(), req.priority(),
                req.description(), actorId);
        return ApiResponse.ok(null);
    }

    /** POST /admin/v1/decisions/{code}/disable?tenantId=xxx — 停用 decision。 */
    @PostMapping("/{code}/disable")
    public ApiResponse<Void> disable(@PathVariable String code,
                                     @RequestParam Long tenantId,
                                     @RequestHeader("X-Actor-Id") String actorId) {
        decisionService.disable(tenantId, code, actorId);
        return ApiResponse.ok(null);
    }

    /** POST /admin/v1/decisions/{code}/enable?tenantId=xxx — 启用 decision。 */
    @PostMapping("/{code}/enable")
    public ApiResponse<Void> enable(@PathVariable String code,
                                    @RequestParam Long tenantId,
                                    @RequestHeader("X-Actor-Id") String actorId) {
        decisionService.enable(tenantId, code, actorId);
        return ApiResponse.ok(null);
    }

    /** GET /admin/v1/decisions?tenantId=xxx — 列出 tenant 下所有 decision。 */
    @GetMapping
    public ApiResponse<List<DecisionDefinition>> list(@RequestParam Long tenantId) {
        return ApiResponse.ok(decisionService.list(tenantId));
    }

    /** GET /admin/v1/decisions/{code} — 单个 decision（详情页加载）。 */
    @GetMapping("/{code}")
    public ApiResponse<DecisionDefinition> get(@PathVariable String code, @RequestParam Long tenantId) {
        return ApiResponse.ok(decisionService.get(tenantId, code));
    }

    /** GET /admin/v1/decisions/{code}/sources — 产出该 decision 的 ACTIVE 规则（兼作下线影响预检）。 */
    @GetMapping("/{code}/sources")
    public ApiResponse<DecisionSourcesResponse> sources(@PathVariable String code, @RequestParam Long tenantId) {
        List<DecisionService.RuleRef> rules = decisionService.findRulesProducingDecision(tenantId, code);
        return ApiResponse.ok(new DecisionSourcesResponse(code, rules, rules.size()));
    }

    /** GET /admin/v1/decisions/usage-counts — tenant 下每个 decision 的被引用计数（列表徽标）。 */
    @GetMapping("/usage-counts")
    public ApiResponse<List<UsageCount>> usageCounts(@RequestParam Long tenantId) {
        return ApiResponse.ok(decisionService.countRuleUsages(tenantId));
    }

    /** decision 写请求体（typed）。 */
    public record DecisionRequest(String code, String name, Integer priority,
                                  String description) {}

    /** Decision 产出来源响应（兼作下线影响面）。 */
    public record DecisionSourcesResponse(String decisionCode,
                                          List<DecisionService.RuleRef> sources, int sourceCount) {}
}
