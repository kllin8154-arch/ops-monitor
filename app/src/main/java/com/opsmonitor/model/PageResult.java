package com.opsmonitor.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 分页响应模型
 */
@Data
@Builder
public class PageResult<T> {

    /** 数据列表 */
    private List<T> items;

    /** 总条数 */
    private long total;

    /** 当前页 */
    private int page;

    /** 每页大小 */
    private int size;

    /** 总页数 */
    private int totalPages;

    public static <T> PageResult<T> of(List<T> allItems, int page, int size) {
        int total = allItems.size();
        int totalPages = (int) Math.ceil((double) total / size);
        int fromIndex = Math.min(page * size, total);
        int toIndex = Math.min(fromIndex + size, total);
        List<T> pageItems = allItems.subList(fromIndex, toIndex);

        return PageResult.<T>builder()
                .items(pageItems)
                .total(total)
                .page(page)
                .size(size)
                .totalPages(totalPages)
                .build();
    }
}
