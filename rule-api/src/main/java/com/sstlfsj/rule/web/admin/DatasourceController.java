package com.sstlfsj.rule.web.admin;

import com.sstlfsj.rule.eval.api.service.DatasourceNameService;
import com.sstlfsj.rule.web.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    /**
     * GET /admin/v1/datasources/{name}/tables — 查该数据源下所有用户表。
     *
     * @param name 数据源逻辑名
     * @return 表名列表（字母序）
     */
    @GetMapping("/{name}/tables")
    public ApiResponse<List<String>> tables(@PathVariable String name) {
        return ApiResponse.ok(datasourceNameService.tables(name));
    }

    /**
     * GET /admin/v1/datasources/{name}/tables/{table}/columns — 查表的列名列表。
     *
     * @param name  数据源逻辑名
     * @param table 表名
     * @return 列名列表（按字段顺序）
     */
    @GetMapping("/{name}/tables/{table}/columns")
    public ApiResponse<List<String>> columns(@PathVariable String name,
                                             @PathVariable String table) {
        return ApiResponse.ok(datasourceNameService.columns(name, table));
    }
}
