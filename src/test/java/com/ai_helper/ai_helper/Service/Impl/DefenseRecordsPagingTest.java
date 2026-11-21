package com.ai_helper.ai_helper.Service.Impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 2026-11-21 接口边界测试回归：答辩记录分页参数归一化（超大页截断为 100，非法值回落默认） */
class DefenseRecordsPagingTest {

    @Test
    void pageSizeIsClampedAndDefaulted() {
        assertEquals(100, DefenseRecordsServiceImpl.safePageSize(100000));
        assertEquals(100, DefenseRecordsServiceImpl.safePageSize(100));
        assertEquals(10, DefenseRecordsServiceImpl.safePageSize(10));
        assertEquals(10, DefenseRecordsServiceImpl.safePageSize(0));
        assertEquals(10, DefenseRecordsServiceImpl.safePageSize(-5));
        assertEquals(10, DefenseRecordsServiceImpl.safePageSize(null));
    }

    @Test
    void pageNumFallsBackToFirstPage() {
        assertEquals(1, DefenseRecordsServiceImpl.safePageNum(null));
        assertEquals(1, DefenseRecordsServiceImpl.safePageNum(0));
        assertEquals(1, DefenseRecordsServiceImpl.safePageNum(-3));
        assertEquals(7, DefenseRecordsServiceImpl.safePageNum(7));
    }
}
