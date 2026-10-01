package com.ai_helper.ai_helper.Controller.teacher;

import com.ai_helper.ai_helper.Service.DefenseRecordsService;
import com.ai_helper.ai_helper.interceptor.RequireRole;
import com.ai_helper.ai_helper.pojo.enums.UserRole;
import com.ai_helper.ai_helper.pojo.vo.DefenseRecordsVo;
import com.ai_helper.ai_helper.result.Result;
import com.github.pagehelper.PageInfo;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.PrintWriter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

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

    public ExportController(DefenseRecordsService defenseRecordsService) {
        this.defenseRecordsService = defenseRecordsService;
    }

    /**
     * 导出全部答辩成绩为 CSV。
     *
     * <p>前端用法：{@code wx.downloadFile}（header 带 Bearer token）下载后，用
     * {@code wx.openDocument} 打开；部分设备不支持 csv，可提示用户文件已下载。</p>
     */
    @GetMapping("/records.csv")
    public void exportRecords(HttpServletResponse response) throws Exception {
        Result<PageInfo<DefenseRecordsVo>> result = defenseRecordsService.getDefenseRecords(1, EXPORT_MAX_ROWS);
        List<DefenseRecordsVo> list = (result != null && result.getData() != null && result.getData().getList() != null)
                ? result.getData().getList()
                : Collections.emptyList();

        String fileName = URLEncoder.encode("答辩成绩.csv", StandardCharsets.UTF_8).replace("+", "%20");
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
        log.info("导出答辩成绩 CSV 完成 - 条数: {}", list.size());
    }

    /**
     * CSV 字段转义：① 含逗号 / 引号 / 换行时用双引号包裹并把内部引号翻倍；
     * ② 防公式注入（CWE-1236）：以 = + - @ Tab 开头的单元格会被 Excel 当公式执行，
     *    姓名/课题名等用户可控字段可能携带此类前缀（如把姓名注册成 =HYPERLINK(...)），
     *    统一前置单引号中和。前置单引号只对 Excel 公式解析生效，单元格文本不受影响。
     */
    private String csv(Object value) {
        if (value == null) {
            return "";
        }
        String s = String.valueOf(value);
        if (s.startsWith("=") || s.startsWith("+") || s.startsWith("-")
                || s.startsWith("@") || s.startsWith("\t") || s.startsWith("\r")) {
            s = "'" + s;
        }
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
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
