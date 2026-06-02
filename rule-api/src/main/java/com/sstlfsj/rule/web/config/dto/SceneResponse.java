package com.sstlfsj.rule.web.config.dto;

import lombok.Data;

/** SceneDef → API 响应 DTO。 */
@Data
public class SceneResponse {
    private Long id;
    private Long tenantId;
    private String code;
    private String name;
    private String description;
    private String dominantMode;
    private String decisionStrategy;
    private String subjectType;
    private String status;
}
