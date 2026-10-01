package com.ai_helper.ai_helper.Controller.teacher;

import com.ai_helper.ai_helper.Service.StatsService;
import com.ai_helper.ai_helper.interceptor.RequireRole;
import com.ai_helper.ai_helper.pojo.enums.UserRole;
import com.ai_helper.ai_helper.result.Result;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 教师端数据总览。
 *
 * <p>【2026-10-01 新增，借鉴 smart-medicine 的系统信息统计】只读聚合，不触碰答辩主链路。
 * 整类要求 teacher 角色（与其它 /teacher/** 接口一致）。</p>
 */
@RestController
@RequestMapping("/teacher/stats")
@RequireRole(UserRole.TEACHER)
public class StatsController {

    @Resource
    private StatsService statsService;

    /**
     * 数据总览：答辩场次 / 平均分 / 课题分布 / 近期趋势。
     */
    @GetMapping("/overview")
    public Result<Map<String, Object>> overview() {
        return statsService.overview();
    }
}
