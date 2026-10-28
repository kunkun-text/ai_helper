package com.ai_helper.ai_helper.Controller.student;

import com.ai_helper.ai_helper.Service.MediaFileService;
import com.ai_helper.ai_helper.interceptor.AuthInterceptor;
import com.ai_helper.ai_helper.mapper.DefenseTopicsMapper;
import com.ai_helper.ai_helper.pojo.dto.TopicDto;
import com.ai_helper.ai_helper.pojo.entity.DefenseQuestions;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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

    /** 【2026-10-27】下载模板时按课题取预设题，直接印进模板 */
    private final DefenseTopicsMapper defenseTopicsMapper;

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
     *
     * <p>【2026-10-27】带 {@code topicId} 时把该课题的**预设题印进模板**：
     * 方向判定就是逐题核对报告有没有讲这些内容，题目先给学生，
     * 才不会出现"学生不知道要写什么、系统判 0 命中"的错杀。</p>
     */
    @GetMapping("/template")
    public ResponseEntity<byte[]> template(@RequestParam(required = false) Integer topicId) {
        String topicName = null;
        String topicDescription = null;
        List<String> questions = new ArrayList<>();
        if (topicId != null) {
            try {
                TopicDto topic = defenseTopicsMapper.getTopicById(topicId);
                if (topic != null) {
                    topicName = topic.getTopicName();
                    topicDescription = topic.getTopicDescription();
                }
                List<DefenseQuestions> rows = defenseTopicsMapper.getDefenseQuestionById(topicId);
                if (rows != null) {
                    for (DefenseQuestions q : rows) {
                        if (q != null && q.getQuestion() != null && !q.getQuestion().isBlank()) {
                            questions.add(q.getQuestion().trim());
                        }
                        if (questions.size() >= 10) {
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                // 取题失败不能挡住下载：退化为空白模板，学生仍可手填
                log.warn("下载模板时读取课题/预设题失败（按空白模板返回）- topicId: {}, 原因: {}",
                        topicId, e.getMessage());
            }
        }
        byte[] body = ReportTemplateBuilder.build(topicName, topicDescription, questions);
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
                AuthInterceptor.currentUserNumber(request), topicId, MediaKind.REPORT, file));
    }

    @GetMapping("/url")
    public Result<Map<String, Object>> getUrl(@RequestParam("topicId") Integer topicId,
                                             HttpServletRequest request) {
        String url = mediaFileService.getFileUrl(AuthInterceptor.currentUserNumber(request), topicId, MediaKind.REPORT);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reportUrl", url);
        data.put("hasReport", !UploadUtils.isBlank(url));
        return Result.success(data);
    }

    @PostMapping("/delete")
    public Result<Map<String, Object>> delete(@RequestParam("topicId") Integer topicId,
                                             HttpServletRequest request) {
        mediaFileService.deleteFile(AuthInterceptor.currentUserNumber(request), topicId, MediaKind.REPORT);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deleted", true);
        return Result.success(data);
    }
}
