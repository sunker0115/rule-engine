package com.sstlfsj.rule.web.admin;

import com.sstlfsj.rule.config.api.service.ConnectorWriteService;
import com.sstlfsj.rule.config.api.service.ConnectorWriteService.ConnectorWriteCommand;
import com.sstlfsj.rule.eval.api.FetchTrace;
import com.sstlfsj.rule.eval.api.service.MetricFetchTestService;
import com.sstlfsj.rule.web.admin.MetricController.TestRequest;
import com.sstlfsj.rule.web.admin.convert.ConnectorConvert;
import com.sstlfsj.rule.web.admin.dto.ConnectorDetailResponse;
import com.sstlfsj.rule.web.admin.dto.ConnectorRequest;
import com.sstlfsj.rule.web.admin.dto.ConnectorResponse;
import com.sstlfsj.rule.web.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 连接器写 API（list/create/update；:test 见 P3）。 */
@RestController
@RequestMapping("/admin/v1/connectors")
@RequiredArgsConstructor
public class ConnectorController {

    private final ConnectorWriteService service;
    private final ConnectorConvert convert;
    private final MetricFetchTestService testService;

    /**
     * GET /admin/v1/connectors — 列出租户内全部 ACTIVE 连接器。
     *
     * @param tenantId 租户 ID
     * @return 连接器列表
     */
    @GetMapping
    public ApiResponse<List<ConnectorResponse>> list(@RequestParam String tenantId) {
        List<ConnectorResponse> data = service.listActive(Long.valueOf(tenantId)).stream()
                .map(convert::toResponse).toList();
        return ApiResponse.ok(data);
    }

    /**
     * GET /admin/v1/connectors/{connectorCode} — 查单个连接器完整信息（含 typed descriptor），供前端编辑器加载。
     *
     * @param connectorCode 连接器编码
     * @param tenantId      租户 ID
     * @return 连接器详情
     */
    @GetMapping("/{connectorCode}")
    public ApiResponse<ConnectorDetailResponse> getByCode(@PathVariable String connectorCode,
                                                          @RequestParam String tenantId) {
        return ApiResponse.ok(convert.toDetailResponse(
                service.getByCode(Long.valueOf(tenantId), connectorCode)));
    }

    /**
     * POST /admin/v1/connectors — 创建连接器（置 ACTIVE，写时校验）。
     *
     * @param tenantId      租户 ID
     * @param connectorCode 连接器编码，作为 query param 传入
     * @param actorId       操作人
     * @param req           写请求体
     * @return 新建行的 id
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Long> create(@RequestParam String tenantId,
                                    @RequestParam String connectorCode,
                                    @RequestHeader("X-Actor-Id") String actorId,
                                    @RequestBody ConnectorRequest req) {
        Long id = service.create(Long.valueOf(tenantId), connectorCode,
                new ConnectorWriteCommand(req.name(), req.descriptor()), actorId);
        return ApiResponse.ok(id);
    }

    /**
     * PUT /admin/v1/connectors/{connectorCode} — 原地更新连接器描述符（不升版）。
     *
     * @param connectorCode 连接器编码
     * @param tenantId      租户 ID
     * @param actorId       操作人
     * @param req           写请求体
     * @return 受影响行数
     */
    @PutMapping("/{connectorCode}")
    public ApiResponse<Integer> update(@PathVariable String connectorCode,
                                       @RequestParam String tenantId,
                                       @RequestHeader("X-Actor-Id") String actorId,
                                       @RequestBody ConnectorRequest req) {
        int n = service.update(Long.valueOf(tenantId), connectorCode,
                new ConnectorWriteCommand(req.name(), req.descriptor()), actorId);
        return ApiResponse.ok(n);
    }

    /**
     * POST /admin/v1/connectors/{connectorCode}/disable — 停用连接器（ACTIVE→DISABLED）。
     *
     * @param connectorCode 连接器编码
     * @param tenantId      租户 ID
     * @param actorId       操作人
     * @return 空响应
     */
    @PostMapping("/{connectorCode}/disable")
    public ApiResponse<Void> disable(@PathVariable String connectorCode,
                                     @RequestParam String tenantId,
                                     @RequestHeader("X-Actor-Id") String actorId) {
        service.disable(Long.valueOf(tenantId), connectorCode, actorId);
        return ApiResponse.ok(null);
    }

    /**
     * POST /admin/v1/connectors/{connectorCode}:test — 直测连接器（不经 metric，传临时样例 vars），返回分阶段 trace。
     *
     * @param connectorCode 连接器编码
     * @param tenantId      租户 ID
     * @param req           样例入参（vars / payload / subjectId）
     * @return 分阶段取数 trace
     */
    @PostMapping("/{connectorCode}:test")
    public ApiResponse<FetchTrace> test(@PathVariable String connectorCode,
                                        @RequestParam String tenantId,
                                        @RequestBody TestRequest req) {
        return ApiResponse.ok(testService.testConnector(Long.valueOf(tenantId), connectorCode,
                req.sampleVars(), req.samplePayload(), req.sampleSubjectId()));
    }
}
