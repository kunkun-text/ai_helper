package com.ai_helper.ai_helper.Controller.student;

import cn.hutool.core.util.IdUtil;
import com.ai_helper.ai_helper.mapper.DefenseRecordsMapper;
import com.ai_helper.ai_helper.result.Result;
import com.aliyun.oss.OSS;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/report")
@RequiredArgsConstructor
@Slf4j
public class ReportUploadController {

    private final OSS ossClient;
    private final DefenseRecordsMapper defenseRecordsMapper;

    @Value("${aliyun.oss.bucketName}")
    private String bucketName;

    @Value("${aliyun.oss.endpoint}")
    private String endpoint;

    /**
     * 上传答辩报告（直接上传，非分片）
     */
    @PostMapping("/upload")
    public Result<Map<String, String>> uploadReport(
            @RequestParam("file") MultipartFile file,
            @RequestParam("userId") String userNumber,
            @RequestParam("topicId") Integer topicId) {

        if (file.isEmpty()) {
            return Result.error("文件不能为空");
        }

        // 校验文件类型
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isEmpty()) {
            return Result.error("文件名无效");
        }

        String suffix = "";
        if (originalFilename.contains(".")) {
            suffix = originalFilename.substring(originalFilename.lastIndexOf("."));
        }
        String objectKey = "report/" + IdUtil.simpleUUID() + suffix;

        try {
            // 上传到阿里云 OSS
            ossClient.putObject(bucketName, objectKey, file.getInputStream());

            // 构建访问 URL
            String cleanEndpoint = endpoint.replaceFirst("^https?://", "");
            String reportUrl = "https://" + bucketName + "." + cleanEndpoint + "/" + objectKey;

            log.info("报告上传 OSS 成功 - objectKey: {}, url: {}", objectKey, reportUrl);

            // 获取内部 userId
            String internalUserId = defenseRecordsMapper.getUserIdByUserNumber(userNumber);
            if (internalUserId == null) {
                return Result.error("未找到对应用户");
            }

            // 原子化保存 report_url（线程安全）
            defenseRecordsMapper.upsertReportUrl(internalUserId, topicId, reportUrl);
            log.info("报告 URL 保存成功 - userId: {}, topicId: {}", internalUserId, topicId);

            Map<String, String> result = new HashMap<>();
            result.put("reportUrl", reportUrl);
            result.put("fileName", originalFilename);
            return Result.success(result);

        } catch (IOException e) {
            log.error("报告上传失败", e);
            return Result.error("上传失败：" + e.getMessage());
        }
    }

    /**
     * 获取报告的 OSS URL
     */
    @GetMapping("/url")
    public Result<Map<String, String>> getReportUrl(
            @RequestParam String userId,
            @RequestParam Integer topicId) {
        String internalUserId = defenseRecordsMapper.getUserIdByUserNumber(userId);
        if (internalUserId == null) {
            return Result.error("未找到对应用户");
        }

        String url = defenseRecordsMapper.getReportUrlByUserIdAndTopicId(internalUserId, topicId);

        Map<String, String> result = new HashMap<>();
        result.put("reportUrl", url != null ? url : "");
        return Result.success(result);
    }
}
