package com.sstlfsj.rule.web.common;

import java.util.List;

/** 统一分页响应：page 从 1 起，items 为当页数据，total 为总记录数。 */
public record PageResponse<T>(List<T> items, long total, int page, int size) {

    /**
     * 构造分页响应。
     *
     * @param items 当页数据
     * @param total 总记录数
     * @param page  当前页码（从 1 起）
     * @param size  每页条数
     * @return 分页响应
     */
    public static <T> PageResponse<T> of(List<T> items, long total, int page, int size) {
        return new PageResponse<>(items, total, page, size);
    }
}
