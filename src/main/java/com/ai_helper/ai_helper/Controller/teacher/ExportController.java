package com.ai_helper.ai_helper.Controller.teacher;

import com.ai_helper.ai_helper.Service.DefenseRecordsService;
import com.ai_helper.ai_helper.util.CsvUtils;
import com.ai_helper.ai_helper.interceptor.RequireRole;
import com.ai_helper.ai_helper.mapper.StatsMapper;
import com.ai_helper.ai_helper.pojo.enums.UserRole;
import com.ai_helper.ai_helper.pojo.vo.DefenseRecordsVo;
import com.ai_helper.ai_helper.result.Result;
import com.github.pagehelper.PageInfo;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.PrintWriter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 答辩成绩导出（CSV）。
 *
 * <p>【2026-10-01 新增】复用 {@link DefenseRecordsService#getDefenseRecords}，不新增 SQL、
 * 不引入 POI 等新依赖（走原生 {@link HttpServletResponse} 输出，UTF-8 BOM 保证 Excel 打开不乱码）。</p>
 *
 * <p>权限：整类 {@code @RequireRole(TEACHER)}，与其它教师接口一致。</p>
 */
@Slf4j
@RestController
@RequestMapping("/teacher/export")
@RequireRole(UserRole.TEACHER)
public class ExportController {

    /** 导出上限：一次最多导出的记录条数（防止超大结果集打爆内存，8G 低功耗机器） */
    private static final int EXPORT_MAX_ROWS = 10000;

    private final DefenseRecordsService defenseRecordsService;

    /** 【N9 · 2026-10-08】按课题导出与课题统计：复用只读聚合 Mapper，不新增写操作 */
    private final StatsMapper statsMapper;

    public ExportController(DefenseRecordsService defenseRecordsService, StatsMapper statsMapper) {
        this.defenseRecordsService = defenseRecordsService;
        this.statsMapper = statsMapper;
    }

    /**
     * 导出全部答辩成绩为 CSV。
     *
     * <p>前端用法：{@code wx.downloadFile}（header 带 Bearer token）下载后，用
     * {@code wx.openDocument} 打开；部分设备不支持 csv，可提示用户文件已下载。</p>
     */
    @GetMapping("/records.csv")
    public void exportRecords(@RequestParam(value = "topicId", required = false) Integer topicId,
                              HttpServletResponse response) throws Exception {
        List<DefenseRecordsVo> list;
        String displayName;
        if (topicId != null) {
            // 【N9 · 2026-10-08】带 topicId 时只导出该课题的成绩（教师按课题发成绩单的场景）
            list = statsMapper.selectRecordsByTopic(topicId, EXPORT_MAX_ROWS);
            displayName = "答辩成绩-课题" + topicId + ".csv";
        } else {
            Result<PageInfo<DefenseRecordsVo>> result = defenseRecordsService.getDefenseRecords(1, EXPORT_MAX_ROWS);
            list = (result != null && result.getData() != null && result.getData().getList() != null)
                    ? result.getData().getList()
                    : Collections.emptyList();
            displayName = "答辩成绩.csv";
        }

        String fileName = URLEncoder.encode(displayName, StandardCharsets.UTF_8).replace("+", "%20");
        response.setContentType("text/csv;charset=UTF-8");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // filename= 为不支持 RFC 5987 的旧客户端兜底；filename*=UTF-8'' 才是中文文件名的标准写法
        response.setHeader("Content-Disposition",
                "attachment; filename=\"records.csv\"; filename*=UTF-8''" + fileName);

        PrintWriter writer = response.getWriter();
        // UTF-8 BOM：让 Excel 正确识别中文
        writer.write('\ufeff');
        // 行分隔符固定 CRLF（writer.println 随服务器平台变化，Linux 上会是 LF）
        writer.write("答辩ID,学生姓名,学号,课题,得分,状态,答辩时间\r\n");
        for (DefenseRecordsVo vo : list) {
            writer.write(String.join(",",
                    csv(vo.getDefenseRecordId()),
                    csv(vo.getUserName()),
                    csv(vo.getUserNumber()),
                    csv(vo.getTopicName()),
                    csv(vo.getScore()),
                    csv(statusText(vo.getStatus())),
                    csv(vo.getDefenseTime() == null ? "" : vo.getDefenseTime().toString())));
            writer.write("\r\n");
        }
        writer.flush();
        log.info("导出答辩成绩 CSV 完成 - 条数: {}, 课题过滤: {}", list.size(), topicId);
    }

    /**
     * 【N9 · 2026-10-08】按课题统计导出：每个课题的题目数 / 答辩场次 / 已完成 / 平均分。
     *
     * <p>与「数据总览」的课题分布同源（都走 {@link StatsMapper#selectTopicStats}），
     * 但导出保留全部课题（总览只展示前 N 个），便于教师做横向对比。</p>
     */
    @GetMapping("/topic-stats.csv")
    public void exportTopicStats(HttpServletResponse response) throws Exception {
        List<Map<String, Object>> stats = statsMapper.selectTopicStats(EXPORT_MAX_ROWS);

        String fileName = URLEncoder.encode("课题统计.csv", StandardCharsets.UTF_8).replace("+", "%20");
        response.setContentType("text/csv;charset=UTF-8");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Content-Disposition",
                "attachment; filename=\"topic-stats.csv\"; filename*=UTF-8''" + fileName);

        PrintWriter writer = response.getWriter();
        writer.write(CsvUtils.BOM);
        writer.write(String.join(",",
                csv("课题ID"), csv("课题名称"), csv("指导教师"), csv("答辩时间"),
                csv("题目数"), csv("答辩场次"), csv("已完成场次"), csv("平均分")));
        writer.write(CsvUtils.CRLF);
        for (Map<String, Object> row : stats) {
            writer.write(String.join(",",
                    csv(row.get("topicId")),
                    csv(row.get("topicName")),
                    csv(row.get("teacherName")),
                    csv(row.get("defenseTime")),
                    csv(row.get("questionCount")),
                    csv(row.get("defenseCount")),
                    csv(row.get("completedCount")),
                    csv(row.get("avgScore"))));
            writer.write(CsvUtils.CRLF);
        }
        writer.flush();
        log.info("导出课题统计 CSV 完成 - 课题数: {}", stats == null ? 0 : stats.size());
    }

    /**
     * CSV 字段转义：① 含逗号 / 引号 / 换行时用双引号包裹并把内部引号翻倍；
     * ② 防公式注入（CWE-1236）：以 = + - @ Tab 开头的单元格会被 Excel 当公式执行，
     *    姓名/课题名等用户可控字段可能携带此类前缀（如把姓名注册成 =HYPERLINK(...)），
     *    统一前置单引号中和。前置单引号只对 Excel 公式解析生效，单元格文本不受影响。
     */
    private String csv(Object value) {
        // 【N10 · 2026-10-08】转义与公式注入中和抽到 CsvUtils，与「题库导入模板」共用同一套口径
        return CsvUtils.escapeCell(value);
    }

    /** 状态枚举转中文 */
    private String statusText(String status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case "pending" -> "进行中";
            case "completed" -> "已完成";
            case "graded" -> "已评分";
            default -> status;
        };
    }
}
