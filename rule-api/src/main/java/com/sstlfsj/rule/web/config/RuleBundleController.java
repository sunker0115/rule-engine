package com.sstlfsj.rule.web.config;

import com.sstlfsj.rule.config.api.dto.RuleBundle;
import com.sstlfsj.rule.config.api.dto.RuleImportResult;
import com.sstlfsj.rule.config.api.service.RuleBundleService;
import com.sstlfsj.rule.web.common.ApiResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 规则批量导出 / 导入入口（B7 / 08-evolution §2.9）。
 * <p>导出为 Bundle JSON 文件下载，导入为 multipart 文件上传；Service 进出 {@link RuleBundle} 对象，
 * 本 Controller 负责对象 ↔ 文件的转换。权限 v1 沿用 X-Actor-Id（EXPORT / PUBLISH 校验留 TODO）。</p>
 */
@RestController
@RequestMapping("/api/v1/rules")
public class RuleBundleController {

    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final RuleBundleService ruleBundleService;
    private final ObjectMapper objectMapper;

    public RuleBundleController(RuleBundleService ruleBundleService, ObjectMapper objectMapper) {
        this.ruleBundleService = ruleBundleService;
        this.objectMapper = objectMapper;
    }

    /**
     * GET /api/v1/rules/export — 按条件导出规则当前 ACTIVE 版本为 Bundle JSON 文件下载。
     * <p>选取优先级：ruleIds 非空 → 按 id 列表；否则 sceneId 非空 → 该场景全部；否则 → 该租户全部。
     * 成功返回 attachment 文件；无可导出规则等错误由 GlobalExceptionHandler 转 JSON 错误体。</p>
     *
     * @param tenantId 租户 id
     * @param ruleIds  规则定义 id 列表（逗号分隔，可选）
     * @param sceneId  场景 id（可选）
     * @return Bundle JSON 文件（Content-Disposition: attachment）
     */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam String tenantId,
                                         @RequestParam(required = false) List<Long> ruleIds,
                                         @RequestParam(required = false) Long sceneId) {
        RuleBundle bundle = ruleBundleService.export(tenantId, ruleIds, sceneId);
        byte[] body = objectMapper.writeValueAsString(bundle).getBytes(StandardCharsets.UTF_8);
        String filename = "rule-bundle-" + tenantId + "-" + LocalDateTime.now().format(FILE_TS) + ".json";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(body);
    }

    /**
     * POST /api/v1/rules/import — 上传 Bundle JSON 文件，幂等批量导入，规则逐条落为 DRAFT 版本。
     *
     * @param tenantId 目标租户 id
     * @param actorId  操作人
     * @param file     Bundle JSON 文件（multipart 字段名 file）
     * @return 导入结果汇总
     */
    @PostMapping("/import")
    public ApiResponse<RuleImportResult> importBundle(@RequestParam String tenantId,
                                                      @RequestHeader("X-Actor-Id") String actorId,
                                                      @RequestParam("file") MultipartFile file) {
        RuleBundle bundle;
        try {
            bundle = objectMapper.readValue(file.getBytes(), RuleBundle.class);
        } catch (Exception e) {
            // 文件读取失败（IOException）或 JSON 反序列化失败 → 400
            throw new IllegalArgumentException("Bundle 文件解析失败: " + e.getMessage());
        }
        return ApiResponse.ok(ruleBundleService.importBundle(tenantId, bundle, actorId));
    }
}
