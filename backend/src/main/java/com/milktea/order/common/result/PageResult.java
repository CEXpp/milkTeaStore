package com.milktea.order.common.result;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 分页响应结构（LLD 3.1）：固定 {@code {list, total, page, size}}，全站分页接口统一使用。
 *
 * @param <T> 列表元素类型
 */
@Data
public class PageResult<T> implements Serializable {

    /** 当前页数据。 */
    private List<T> list;

    /** 满足条件的总条数。 */
    private long total;

    /** 当前页码（从 1 起）。 */
    private int page;

    /** 每页条数（默认 20，最大 100）。 */
    private int size;

    public static <T> PageResult<T> of(List<T> list, long total, int page, int size) {
        PageResult<T> result = new PageResult<>();
        result.setList(list);
        result.setTotal(total);
        result.setPage(page);
        result.setSize(size);
        return result;
    }
}
