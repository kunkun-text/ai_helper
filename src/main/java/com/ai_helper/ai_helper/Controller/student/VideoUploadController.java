package com.ai_helper.ai_helper.Controller.student;

import com.ai_helper.ai_helper.Service.ChunkUploadService;
import com.ai_helper.ai_helper.Service.MediaFileService;
import com.ai_helper.ai_helper.Service.VideoProcessingService;
import com.ai_helper.ai_helper.pojo.enums.MediaKind;
import com.ai_helper.ai_helper.result.Result;
import com.ai_helper.ai_helper.util.UploadUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 答辩视频上传。
 *
 * <p>两条路径：大视频走分片（init → part × N → complete），小视频走单次直传（upload），
 * 由前端根据 {@code /api/video/policy} 返回的规则选择。</p>
 *
 * <p>所有接口的「当前用户」一律取自登录态（AuthInterceptor 写入的 request attribute），
 * 学生无法通过改参数读写他人文件。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/video")
@RequiredArgsConstructor
public class VideoUploadController {

    private final ChunkUploadService chunkUploadService;
    private final MediaFileService mediaFileService;
    private final VideoProcessingService videoProcessingService;

    /** 上传规则：前端据此做前置校验并决定分片 or 直传 */
    @GetMapping("/policy")
    public Result<Map<String, Object>> policy() {
        return Result.success(mediaFileService.describePolicy(MediaKind.VIDEO));
    }

    /** 初始化分片上传 */
    @PostMapping("/init")
    public Result<Map<String, Object>> init(@RequestParam("fileName") String fileName,
                                           @RequestParam("fileSize") long fileSize,
                                           @RequestParam("topicId") Integer topicId,
                                           HttpServletRequest request) {
        return Result.success(chunkUploadService.init(currentUserNumber(request), topicId, fileName, fileSize));
    }

    /** 上传单个分片，请求体为分片原始字节（Content-Type: application/octet-stream） */
    @PostMapping("/part")
    public Result<Map<String, Object>> uploadPart(@RequestParam("uploadId") String uploadId,
                                                 @RequestParam("partNumber") int partNumber,
                                                 @RequestBody byte[] data,
                                                 HttpServletRequest request) {
        return Result.success(chunkUploadService.savePart(currentUserNumber(request), uploadId, partNumber, data));
    }

    /** 查询已成功的分片，失败重试时用于续传 */
    @GetMapping("/status")
    public Result<Map<String, Object>> status(@RequestParam("uploadId") String uploadId,
                                             HttpServletRequest request) {
        return Result.success(chunkUploadService.status(currentUserNumber(request), uploadId));
    }

    /** 全部分片完成后合并入库 */
    @PostMapping("/complete")
    public Result<Map<String, Object>> complete(@RequestParam("uploadId") String uploadId,
                                               HttpServletRequest request) {
        return Result.success(chunkUploadService.complete(currentUserNumber(request), uploadId));
    }

    /** 放弃本次上传，清理半成品文件 */
    @PostMapping("/abort")
    public Result<Map<String, Object>> abort(@RequestParam("uploadId") String uploadId,
                                            HttpServletRequest request) {
        chunkUploadService.abort(currentUserNumber(request), uploadId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("aborted", true);
        return Result.success(data);
    }

    /** 小视频：单次直传（不分片） */
    @PostMapping("/upload")
    public Result<Map<String, Object>> upload(@RequestParam("file") MultipartFile file,
                                             @RequestParam("topicId") Integer topicId,
                                             HttpServletRequest request) {
        return Result.success(mediaFileService.saveWholeFile(
                currentUserNumber(request), topicId, MediaKind.VIDEO, file));
    }

    /** 查询某个题目下已上传的视频 */
    @GetMapping("/url")
    public Result<Map<String, Object>> getUrl(@RequestParam("topicId") Integer topicId,
                                             HttpServletRequest request) {
        String url = mediaFileService.getFileUrl(currentUserNumber(request), topicId, MediaKind.VIDEO);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("videoUrl", url);
        data.put("hasVideo", !UploadUtils.isBlank(url));
        return Result.success(data);
    }

    /** 删除某个题目下已上传的视频（同时清理磁盘文件） */
    @PostMapping("/delete")
    public Result<Map<String, Object>> delete(@RequestParam("topicId") Integer topicId,
                                             HttpServletRequest request) {
        mediaFileService.deleteFile(currentUserNumber(request), topicId, MediaKind.VIDEO);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deleted", true);
        return Result.success(data);
    }

    /** 查询视频后处理进度（合并入库后异步执行） */
    @GetMapping("/processing-status")
    public Result<Map<String, Object>> processingStatus(@RequestParam("processingId") String processingId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", videoProcessingService.getProcessingStatus(processingId));
        return Result.success(data);
    }

    private String currentUserNumber(HttpServletRequest request) {
        Object value = request.getAttribute("userNumber");
        return value == null ? null : String.valueOf(value);
    }
}
