package com.sstlfsj.rule.web.sdk;

import com.sstlfsj.rule.eval.internal.snapshot.SceneSnapshotLoader;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.web.common.ApiResponse;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** SDK 快照端点：供 SnapshotPoller 启动加载和增量热更新。 */
@RestController
@RequestMapping("/sdk/v1")
public class SdkSnapshotController {

    private final SceneSnapshotLoader snapshotLoader;

    public SdkSnapshotController(SceneSnapshotLoader snapshotLoader) {
        this.snapshotLoader = snapshotLoader;
    }

    /**
     * 拉取规则版本快照。
     *
     * @param tenantId 租户 ID（必填）
     * @param scenes   场景编码列表，逗号分隔；不传则加载该租户所有快照
     * @param since    增量拉取时间戳（毫秒），预留参数，暂不过滤
     * @return 快照列表（已去重）
     */
    @GetMapping("/snapshots")
    public ApiResponse<List<RuleVersionSnapshot>> getSnapshots(
            @RequestParam String tenantId,
            @RequestParam(required = false) String scenes,
            @RequestParam(required = false) Long since) {
        List<RuleVersionSnapshot> result = new ArrayList<>();
        if (scenes != null && !scenes.isBlank()) {
            for (String scene : Arrays.stream(scenes.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).toList()) {
                Map<String, List<RuleVersionSnapshot>> byType =
                        snapshotLoader.loadByScene(tenantId, scene);
                byType.values().forEach(result::addAll);
            }
        } else {
            Map<String, Map<String, List<RuleVersionSnapshot>>> all =
                    snapshotLoader.loadAll();
            all.values().forEach(inner -> inner.values().forEach(result::addAll));
        }
        return ApiResponse.ok(result.stream().distinct().toList());
    }
}
