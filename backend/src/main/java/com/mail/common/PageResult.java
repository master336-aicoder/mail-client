package com.mail.common;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 通用分页结果：页码从 1 开始。
 */
@Data
@AllArgsConstructor
public class PageResult<T> {

    private long total;

    private int page;

    private int size;

    private List<T> list;
}
