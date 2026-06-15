package com.sstlfsj.rule.web.admin;

import com.sstlfsj.rule.config.api.service.ConnectorWriteService;
import com.sstlfsj.rule.config.api.service.ConnectorWriteService.ConnectorWriteCommand;
import com.sstlfsj.rule.web.admin.convert.ConnectorConvert;
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
}
