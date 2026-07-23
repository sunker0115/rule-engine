package com.sstlfsj.rule.web.admin.dto;

/** 表达式实时诊断响应。 */
public record ValidateExpressionResponse(
        boolean valid,
        /** valid=false 时的人类可读错误信息（含行号列号），valid=true 时为 null。 */
        String error
) {}
