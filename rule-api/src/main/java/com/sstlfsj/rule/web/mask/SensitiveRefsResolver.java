package com.sstlfsj.rule.web.mask;

import com.sstlfsj.rule.audit.api.service.AuditService;
import com.sstlfsj.rule.config.api.service.SceneService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SensitiveRefsResolver {

    private static final Logger log = LoggerFactory.getLogger(SensitiveRefsResolver.class);
    private final SceneService sceneService;
    private final AuditService auditService;

    /** 解析会话所属场景的 live 敏感集（trace / replay 读时脱敏）。失败返回 null（masker fail-closed 全抹）。 */
    public SceneService.SensitiveRefs forSession(Long tenantId, Long sessionId) {
        try {
            String sceneCode = auditService.getSessionSceneCode(tenantId, sessionId);
            if (sceneCode == null) return null;
            return sceneService.getSensitiveRefs(tenantId, sceneCode);
        } catch (RuntimeException e) {
            log.warn("getSensitiveRefs 失败，trace 读时脱敏 fail-closed 全抹: tenantId={}, sessionId={}", tenantId, sessionId, e);
            return null;
        }
    }

    /** 解析 (租户, 场景) 的 live 敏感集（dry-run 读时脱敏）。失败返回 null。 */
    public SceneService.SensitiveRefs forScene(Long tenantId, String sceneCode) {
        try {
            return sceneService.getSensitiveRefs(tenantId, sceneCode);
        } catch (RuntimeException e) {
            log.warn("getSensitiveRefs 失败，dry-run trace 读时脱敏 fail-closed 全抹: tenantId={}, sceneCode={}", tenantId, sceneCode, e);
            return null;
        }
    }
}
