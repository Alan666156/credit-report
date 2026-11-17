package com.workbuddy.pbccrc.common;

import lombok.Data;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * 简单分页结果，承载总数、页码、每页条数与当前页数据。
 *
 * @param <T> 列表元素类型
 * @author Alan
 * @date 2026-11-17
 */
@Data
public class PageResult<T> implements Serializable {
    private long total;
    private int pageNum;
    private int pageSize;
    private List<T> list;

    /**
     * 构造分页结果；列表为空时置为空集合而非 null。
     *
     * @param total    总记录数
     * @param pageNum  当前页码
     * @param pageSize 每页条数
     * @param list     当前页数据列表
     * @param <T>      列表元素类型
     * @return 分页结果
     */
    public static <T> PageResult<T> of(long total, int pageNum, int pageSize, List<T> list) {
        PageResult<T> p = new PageResult<>();
        p.setTotal(total);
        p.setPageNum(pageNum);
        p.setPageSize(pageSize);
        p.setList(list == null ? Collections.emptyList() : list);
        return p;
    }
}
