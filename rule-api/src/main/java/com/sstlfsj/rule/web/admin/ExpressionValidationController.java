package com.sstlfsj.rule.web.admin;

import com.sstlfsj.rule.config.internal.expression.ExpressionValidationService;
import com.sstlfsj.rule.web.admin.dto.ValidateExpressionRequest;
import com.sstlfsj.rule.web.admin.dto.ValidateExpressionResponse;
import com.sstlfsj.rule.web.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 表达式实时类型诊断端点：编辑期 keystroke 级校验，复用发布期 typeCheck。 */
@RestController
@RequestMapping("/admin/v1/expressions")
public class ExpressionValidationController {

    private final ExpressionValidationService validationService;

    public ExpressionValidationController(ExpressionValidationService validationService) {
        this.validationService = validationService;
    }

    @PostMapping("/validate")
    public ApiResponse<ValidateExpressionResponse> validate(@Valid @RequestBody ValidateExpressionRequest req) {
        String error = validationService.validate(req.tenantId(), req.sceneCode(), req.lang(), req.source());
        return ApiResponse.ok(new ValidateExpressionResponse(error == null, error));
    }
}
