package com.sstlfsj.rule.web.stub;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 本地调试用 stub 上游服务——模拟外部 HTTP 接口，供测试连接器 → 指标 → 规则取数完整链路。
 *
 * <p>用法：连接器 endpointRef = local-stub（application-local.yml 里配好 base-url=http://localhost:8080），
 * descriptor 的 pathTemplate 随意，如 /stub/score/{subjectId}。</p>
 *
 * <ul>
 *   <li>默认返回 {"code":0,"data":{"score":88,"level":"LOW_RISK"}}</li>
 *   <li>?score=N 控制返回分数</li>
 *   <li>?fail=true 返回 {"code":1,"msg":"stub error"}（用于测试 successWhen 不命中 + errorMapping）</li>
 * </ul>
 */
@RestController
@RequestMapping("/stub")
public class StubUpstreamController {

    private static final Logger log = LoggerFactory.getLogger(StubUpstreamController.class);

    /**
     * 通用打分接口：GET /stub/score/{subjectId}
     * 路径变量 subjectId 仅用于日志，便于区分不同主体的请求。
     */
    @GetMapping("/score/{subjectId}")
    public Map<String, Object> score(
            @PathVariable String subjectId,
            @RequestParam(defaultValue = "88") int score,
            @RequestParam(defaultValue = "false") boolean fail) {
        log.info("[stub] score request: subjectId={}, score={}, fail={}", subjectId, score, fail);
        if (fail) {
            return Map.of("code", 1, "msg", "stub error");
        }
        String level = score >= 80 ? "LOW_RISK" : score >= 50 ? "MEDIUM_RISK" : "HIGH_RISK";
        return Map.of("code", 0, "data", Map.of("score", score, "level", level));
    }

    /**
     * 通用 catch-all：GET /stub/**（path 随意，不依赖特定路由时用）
     * 同样支持 ?score=N 和 ?fail=true。
     */
    @GetMapping("/**")
    public Map<String, Object> catchAll(
            @RequestParam(defaultValue = "88") int score,
            @RequestParam(defaultValue = "false") boolean fail) {
        if (fail) {
            return Map.of("code", 1, "msg", "stub error");
        }
        String level = score >= 80 ? "LOW_RISK" : score >= 50 ? "MEDIUM_RISK" : "HIGH_RISK";
        return Map.of("code", 0, "data", Map.of("score", score, "level", level));
    }
}
