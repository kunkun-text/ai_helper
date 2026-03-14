package com.ai_helper.ai_helper.result;

import lombok.Data;
import lombok.Getter;

import java.util.List;

@Data
@Getter
public class PageResult<T> {
    // Getters and Setters
    private List<T> list;
    private long total;
    private int pageNum;
    private int pageSize;
    private int pages;

    public PageResult(List<T> list, long total, int pageNum, int pageSize) {
        this.list = list;
        this.total = total;
        this.pageNum = pageNum;
        this.pageSize = pageSize;
        this.pages = (int) Math.ceil((double) total / pageSize);
    }

    public void setList(List<T> list) { this.list = list; }

    public void setTotal(long total) { this.total = total; }

    public void setPageNum(int pageNum) { this.pageNum = pageNum; }

    public void setPageSize(int pageSize) { this.pageSize = pageSize; }

    public void setPages(int pages) { this.pages = pages; }
}
