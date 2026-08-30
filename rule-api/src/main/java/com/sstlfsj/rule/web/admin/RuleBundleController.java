package com.sstlfsj.rule.web.admin;

import com.sstlfsj.rule.config.api.dto.ImportDiffReport;
import com.sstlfsj.rule.config.api.dto.ImportPolicy;
import com.sstlfsj.rule.config.api.dto.RuleBundle;
import com.sstlfsj.rule.config.api.service.RuleBundleService;
import com.sstlfsj.rule.config.internal.bundle.RuleImportService.ImportConflictException;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.web.common.ApiException;
import com.sstlfsj.rule.web.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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
 * 规则批量导出 / 导入入口（Bundle v2）。
 *
 * <p>import 支持：
 * <ul>
 *   <li>{@code dryRun=true}：返回 diff 报告但不落库；</li>
 *   <li>{@code policy}：SKIP（默认）/ OVERWRITE / ABORT 三种冲突策略；</li>
 *   <li>ABORT 策略有冲突时返回 422 + 冲突详情。</li>
 * </ul>
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/v1/rules")
public class RuleBundleController {

    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final RuleBundleService ruleBundleService;
    private final ObjectMapper objectMapper;

    /**
     * GET /admin/v1/rules/export — 按条件导出规则当前 ACTIVE 版本为 Bundle v2 JSON 文件。
     *
     * @param tenantId 租户 id
     * @param ruleIds  规则定义 id 列表（逗号分隔，可选）
     * @param sceneCode 场景编码（可选）
     * @return Bundle JSON 文件（Content-Disposition: attachment）
     */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam Long tenantId,
                                         @RequestParam(required = false) List<Long> ruleIds,
                                         @RequestParam(required = false) String sceneCode,
                                         @RequestParam(defaultValue = "bundle") String format) {
        byte[] body;
        String suffix;
        if ("snapshot".equals(format)) {
            List<RuleVersionSnapshot> snapshots = ruleBundleService.exportSnapshots(tenantId, ruleIds, sceneCode);
            body = objectMapper.writeValueAsString(snapshots).getBytes(StandardCharsets.UTF_8);
            suffix = "snapshots";
        } else {
            RuleBundle bundle = ruleBundleService.export(tenantId, ruleIds, sceneCode);
            body = objectMapper.writeValueAsString(bundle).getBytes(StandardCharsets.UTF_8);
            suffix = "bundle";
        }
        String filename = "rule-" + suffix + "-" + tenantId + "-" + LocalDateTime.now().format(FILE_TS) + ".json";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(body);
    }

    /**
     * POST /admin/v1/rules/import — 导入 Bundle v2。
     *
     * <p>{@code dryRun=true} 时返回 diff 报告但不落库；{@code dryRun=false}（默认）真实 apply。
     * ABORT 策略有冲突时返回 422 Unprocessable Content + conflicts 列表。</p>
     *
     * @param tenantId 目标租户 id
     * @param policy   冲突策略（SKIP / OVERWRITE / ABORT，默认 SKIP）
     * @param dryRun   true = 仅预览 diff，不落库
     * @param actorId  操作人（X-Actor-Id）
     * @param file     Bundle v2 JSON 文件（multipart 字段名 file）
     * @return diff 报告（dry-run 和 apply 均返回）
     */
    @PostMapping("/import")
    public ResponseEntity<ApiResponse<ImportDiffReport>> importBundle(
            @RequestParam Long tenantId,
            @RequestParam(defaultValue = "SKIP") ImportPolicy policy,
            @RequestParam(defaultValue = "false") boolean dryRun,
            @RequestHeader("X-Actor-Id") String actorId,
            @RequestParam("file") MultipartFile file) {
        RuleBundle bundle;
        try {
            bundle = objectMapper.readValue(file.getBytes(), RuleBundle.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Bundle 文件解析失败: " + e.getMessage());
        }
        try {
            ImportDiffReport report = ruleBundleService.importBundle(tenantId, bundle, policy, dryRun, actorId);
            return ResponseEntity.ok(ApiResponse.ok(report));
        } catch (ImportConflictException e) {
            // ABORT 策略有冲突：422 + conflicts 详情
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "IMPORT_CONFLICT", "Bundle import aborted: conflicts found");
        }
    }
}
