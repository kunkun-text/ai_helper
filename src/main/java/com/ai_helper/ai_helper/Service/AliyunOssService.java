package com.ai_helper.ai_helper.Service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;

@Service
@Slf4j
public class AliyunOssService {

    @Value("${aliyun.oss.endpoint}")
    private String endpoint;

    @Value("${aliyun.oss.accessKeyId}")
    private String accessKeyId;

    @Value("${aliyun.oss.accessKeySecret}")
    private String accessKeySecret;

    @Value("${aliyun.oss.bucketName}")
    private String bucketName;

    /**
     * 删除 OSS 文件
     * @param fileUrl 文件 URL 或 ObjectKey
     * @return 删除是否成功
     */
    public boolean deleteFile(String fileUrl) {
        if (fileUrl == null || fileUrl.isEmpty()) {
            log.warn("文件 URL 为空，无需删除");
            return false;
        }

        OSS ossClient = null;
        try {
            // 从完整 URL 中提取 ObjectKey
            String objectKey = extractObjectKey(fileUrl);

            ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);

            if (ossClient.doesObjectExist(bucketName, objectKey)) {
                ossClient.deleteObject(bucketName, objectKey);
                log.info("成功删除 OSS 文件：{}", objectKey);
                return true;
            } else {
                log.warn("OSS 文件不存在：{}", objectKey);
                return false;
            }
        } catch (Exception e) {
            log.error("删除 OSS 文件失败：{}", fileUrl, e);
            return false;
        } finally {
            if (ossClient != null) {
                try {
                    ossClient.shutdown();
                } catch (Exception e) {
                    log.error("关闭 OSS 客户端失败", e);
                }
            }
        }
    }

    /**
     * 从完整 URL 中提取 ObjectKey
     * 例如：https://bucketname.oss-cn-hangzhou.aliyuncs.com/videos/xxx.mp4 -> videos/xxx.mp4
     */
    private String extractObjectKey(String fileUrl) {
        if (fileUrl.startsWith("http")) {
            // 移除协议和域名部分
            int firstSlash = fileUrl.indexOf('/', fileUrl.indexOf("//") + 2);
            if (firstSlash > 0) {
                return fileUrl.substring(firstSlash + 1);
            }
        }
        // 如果已经是 ObjectKey，直接返回
        return fileUrl;
    }
}
