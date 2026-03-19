package com.ai_helper.ai_helper.util;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class OssRedisUploadUtil {

    private final OSS ossClient;
    private final RedisTemplate<String, Object> redisTemplate;

    @Value("${aliyun.oss.bucketName}")
    private String bucketName;

    @Value("${aliyun.oss.dir}")
    private String fileDir;
    
    @Value("${aliyun.oss.endpoint}")
    private String endpoint;

    // 分片大小 20MB（生产推荐）
    private static final long PART_SIZE = 20 * 1024 * 1024L;

    // Redis 存储前缀
    private static final String UPLOAD_PREFIX = "oss:upload:";

    /**
     * 初始化分片上传
     */
    public String initMultipartUpload(String fileName) {
        String key = fileDir + fileName;
        InitiateMultipartUploadRequest request = new InitiateMultipartUploadRequest(bucketName, key);
        InitiateMultipartUploadResult result = ossClient.initiateMultipartUpload(request);
        String uploadId = result.getUploadId();

        // 初始化 Redis 存储分片列表，过期时间 24 小时
        redisTemplate.opsForList().rightPush(UPLOAD_PREFIX + uploadId, new ArrayList<Map<String, Object>>());
        redisTemplate.expire(UPLOAD_PREFIX + uploadId, 24, TimeUnit.HOURS);

        return uploadId;
    }

    /**
     * 上传分片 + 保存 ETag 到 Redis
     */
    public void uploadPart(MultipartFile file, String uploadId, String fileName, int partNumber) throws IOException {
        String key = fileDir + fileName;

        UploadPartRequest request = new UploadPartRequest();
        request.setBucketName(bucketName);
        request.setKey(key);
        request.setUploadId(uploadId);
        request.setPartNumber(partNumber);
        request.setPartSize(file.getSize());

        try (InputStream in = file.getInputStream()) {
            request.setInputStream(in);
            PartETag partETag = ossClient.uploadPart(request).getPartETag();

            // 存入 Map 而不是直接存对象
            Map<String, Object> partInfo = new HashMap<>();
            partInfo.put("partNumber", partETag.getPartNumber());
            partInfo.put("eTag", partETag.getETag());
            
            // 存入 Redis
            redisTemplate.opsForList().rightPush(UPLOAD_PREFIX + uploadId, partInfo);
            // 延长过期时间
            redisTemplate.expire(UPLOAD_PREFIX + uploadId, 24, TimeUnit.HOURS);
        }
    }

    /**
     * 获取所有分片 ETag
     */
    public List<PartETag> getPartETags(String uploadId) {
        List<Object> list = redisTemplate.opsForList().range(UPLOAD_PREFIX + uploadId, 0, -1);
        List<PartETag> tags = new ArrayList<>();
        if (list != null) {
            for (Object obj : list) {
                if (obj instanceof Map) {
                    Map<String, Object> map = (Map<String, Object>) obj;
                    Integer partNumber = (Integer) map.get("partNumber");
                    String eTag = (String) map.get("eTag");
                    tags.add(new PartETag(partNumber, eTag));
                }
            }
        }
        return tags;
    }

    /**
     * 完成上传 + 合并 + 删除 Redis
     */
    public String completeMultipartUpload(String fileName, String uploadId) {
        String key = fileDir + fileName;
        List<PartETag> partETags = getPartETags(uploadId);


        CompleteMultipartUploadRequest request = new CompleteMultipartUploadRequest(
                bucketName,
                key,
                uploadId,
                partETags
        );

        ossClient.completeMultipartUpload(request);
        // 上传完成删除 Redis
        redisTemplate.delete(UPLOAD_PREFIX + uploadId);
        
        return "https://" + bucketName + "." + endpoint.replaceFirst("^https?://", "") + "/" + key;
    }

    /**
     * 取消上传 + 删除 Redis
     */
    public void abortUpload(String fileName, String uploadId) {
        String key = fileDir + fileName;
        AbortMultipartUploadRequest request = new AbortMultipartUploadRequest(bucketName, key, uploadId);
        ossClient.abortMultipartUpload(request);
        redisTemplate.delete(UPLOAD_PREFIX + uploadId);
    }
}