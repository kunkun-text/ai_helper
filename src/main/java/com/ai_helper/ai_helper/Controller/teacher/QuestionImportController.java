package com.ai_helper.ai_helper.Controller.teacher;

import com.ai_helper.ai_helper.Service.QuestionImportService;
import com.ai_helper.ai_helper.interceptor.AuthInterceptor;
import com.ai_helper.ai_helper.interceptor.RequireRole;
import com.ai_helper.ai_helper.pojo.enums.UserRole;
import com.ai_helper.ai_helper.pojo.vo.QuestionImportResultVo;
import com.ai_helper.ai_helper.result.Result;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 教师端题库批量导入（N10 · 2026-10-08）。
 *
 * <p>两个接口：{@code GET /teacher/questions/import-template} 下载 CSV 模板；
 * {@code POST /teacher/questions/import} 上传 CSV 批量导入到指定课题。</p>
 *
 * <p><b>鉴权</b>：{@code /teacher/**} 已在 {@code WebConfig.PROTECTED_PATHS} 内，
 * 且整类 {@code @RequireRole(TEACHER)}；身份一律取登录态（{@code AuthInterceptor.currentUserNumber}），
 * 课题归属校验在 Service 内完成。</p>
 */
@Slf4j
@RestController
@RequestMapping("/teacher/questions")
@RequireRole(UserRole.TEACHER)
public class QuestionImportController {

    @Resource
    private QuestionImportService questionImportService;

    /**
     * 下载题库导入模板（CSV，UTF-8 BOM，Excel/WPS 可直接打开）。
     *
     * <p>小程序端用 {@code wx.downloadFile}（header 带 Bearer token）下载后交给 {@code wx.openDocument}；
     * 部分机型不支持 csv 预览，此时提示用户「文件已下载，可在微信/文件里查看」。</p>
     */
    @GetMapping("/import-template")
    public void downloadTemplate(HttpServletResponse response) throws IOException {
        byte[] csv = questionImportService.buildTemplateCsv();
        String fileName = URLEncoder.encode("题库导入模板.csv", StandardCharsets.UTF_8).replace("+", "%20");
        response.setContentType("text/csv;charset=UTF-8");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentLength(csv.length);
        // filename= 为旧客户端兜底，filename*=UTF-8'' 才是中文文件名的标准写法
        response.setHeader("Content-Disposition",
                "attachment; filename=\"questions-template.csv\"; filename*=UTF-8''" + fileName);
        response.getOutputStream().write(csv);
        response.flushBuffer();
        log.info("下发题库导入模板 - {} 字节", csv.length);
    }

    /**
     * 批量导入题库。
     *
     * @param file   CSV 文件（第一列题目、第二列标准答案）
     * @param topicId 目标课题
     * @param mode   {@code append}（默认，追加并自动跳过重复题）/ {@code replace}（覆盖：先清空原题目）
     */
    @PostMapping("/import")
    public Result<QuestionImportResultVo> importQuestions(@RequestParam("file") MultipartFile file,
                                                         @RequestParam("topicId") Integer topicId,
                                                         @RequestParam(value = "mode", defaultValue = "append") String mode,
                                                         HttpServletRequest request) {
        boolean replace = "replace".equalsIgnoreCase(mode);
        return Result.success(questionImportService.importCsv(
                AuthInterceptor.currentUserNumber(request), topicId, replace, file));
    }
}
