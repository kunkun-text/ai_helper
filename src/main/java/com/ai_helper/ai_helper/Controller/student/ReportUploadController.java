package com.ai_helper.ai_helper.Controller.student;

import com.ai_helper.ai_helper.Service.MediaFileService;
import com.ai_helper.ai_helper.pojo.enums.MediaKind;
import com.ai_helper.ai_helper.result.Result;
import com.ai_helper.ai_helper.util.ReportTemplateBuilder;
import com.ai_helper.ai_helper.util.UploadUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 答辩报告上传。
 *
 * <p>报告体积小，走单次直传，与视频共用 {@link MediaFileService} 的存储与校验逻辑。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/report")
@RequiredArgsConstructor
public class ReportUploadController {

    private final MediaFileService mediaFileService;

    /** 上传规则：前端据此做前置校验 */
    @GetMapping("/policy")
    public Result<Map<String, Object>> policy() {
        return Result.success(mediaFileService.describePolicy(MediaKind.REPORT));
    }

    /**
     * 下载答辩报告模板（.docx）。
     *
     * <p>模板类型与系统唯一可分析的类型保持一致（docx），并在正文前写明填写须知，
     * 让学生知道"在哪个文件里填、怎么填才能被模型评判"。</p>
     */
    @GetMapping("/template")
    public ResponseEntity<byte[]> template() {
        byte[] body = ReportTemplateBuilder.build();
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename("AI答辩报告模板.docx", StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType
                        .parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .contentLength(body.length)
                .body(body);
    }

    @PostMapping("/upload")
    public Result<Map<String, Object>> upload(@RequestParam("file") MultipartFile file,
                                             @RequestParam("topicId") Integer topicId,
                                             HttpServletRequest request) {
        return Result.success(mediaFileService.saveWholeFile(
                currentUserNumber(request), topicId, MediaKind.REPORT, file));
    }

    @GetMapping("/url")
    public Result<Map<String, Object>> getUrl(@RequestParam("topicId") Integer topicId,
                                             HttpServletRequest request) {
        String url = mediaFileService.getFileUrl(currentUserNumber(request), topicId, MediaKind.REPORT);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reportUrl", url);
        data.put("hasReport", !UploadUtils.isBlank(url));
        return Result.success(data);
    }

    @PostMapping("/delete")
    public Result<Map<String, Object>> delete(@RequestParam("topicId") Integer topicId,
                                             HttpServletRequest request) {
        mediaFileService.deleteFile(currentUserNumber(request), topicId, MediaKind.REPORT);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deleted", true);
        return Result.success(data);
    }

    private String currentUserNumber(HttpServletRequest request) {
        Object value = request.getAttribute("userNumber");
        return value == null ? null : String.valueOf(value);
    }
}
