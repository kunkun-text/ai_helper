package com.ai_helper.ai_helper.Controller.student;

import cn.hutool.core.util.IdUtil;
import com.ai_helper.ai_helper.result.Result;
import com.ai_helper.ai_helper.util.OssRedisUploadUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/video")
@CrossOrigin  // 生产可配置指定域名
@RequiredArgsConstructor
public class VideoUploadController {

    private final OssRedisUploadUtil ossRedisUploadUtil;

    /**
     * 1. 初始化上传
     */
    @PostMapping("/init")
    public Result<Map<String, String>> init(@RequestParam String fileName) {
        String suffix = fileName.substring(fileName.lastIndexOf("."));
        String uniqueName = IdUtil.simpleUUID() + suffix;
        String uploadId = ossRedisUploadUtil.initMultipartUpload(uniqueName);
        
        Map<String, String> result = new HashMap<>();
        result.put("uploadId", uploadId);
        result.put("fileName", uniqueName);
        
        return Result.success(result);
    }

    /**
     * 2. 上传分片
     */
    @PostMapping("/upload")
    public String upload(
            @RequestParam MultipartFile file,
            @RequestParam String uploadId,
            @RequestParam String fileName,
            @RequestParam Integer partNumber
    ) throws Exception {
        ossRedisUploadUtil.uploadPart(file, uploadId, fileName, partNumber);
        return "分片上传成功：" + partNumber;
    }

    /**
     * 3. 完成上传（合并）
     */
    @PostMapping("/complete")
    public String complete(
            @RequestParam String uploadId,
            @RequestParam String fileName
    ) {
        String url = ossRedisUploadUtil.completeMultipartUpload(fileName, uploadId);
        return "上传成功！视频地址：" + url;
    }

    /**
     * 4. 取消上传
     */
    @PostMapping("/abort")
    public String abort(
            @RequestParam String uploadId,
            @RequestParam String fileName
    ) {
        ossRedisUploadUtil.abortUpload(fileName, uploadId);
        return "已取消上传";
    }
}