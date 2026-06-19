package com.sstlfsj.rule.web.admin;

import com.sstlfsj.rule.eval.api.service.DatasourceNameService;
import com.sstlfsj.rule.web.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/** 已注册数据源名列表接口（供 OUTCOME_INGESTION 创建表单数据源 Select）。 */
@RestController
@RequestMapping("/admin/v1/datasources")
@RequiredArgsConstructor
public class DatasourceController {

    private final DatasourceNameService datasourceNameService;

    /**
     * GET /admin/v1/datasources — 返回 MetricDataSourceRegistry 中已注册的数据源名列表（已排序）。
     *
     * @return 数据源名列表
     */
    @GetMapping
    public ApiResponse<List<String>> list() {
        Set<String> names = datasourceNameService.registeredNames();
        return ApiResponse.ok(names.stream().sorted().toList());
    }
}
