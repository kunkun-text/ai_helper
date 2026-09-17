package com.ai_helper.ai_helper.Service.Impl;

import com.ai_helper.ai_helper.Service.FileStorageService;
import com.ai_helper.ai_helper.Service.VideoProcessingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 视频后处理（占位实现）。
 *
 * <p>说明：分片上传的 complete 阶段已经把 video_url 写入答辩记录，
 * 本类不再重复写库，只负责「后续处理」这件事本身与状态维护。</p>
 *
 * <p>状态存 Redis（原先存进程内 ConcurrentHashMap，进程重启即丢，
 * 且 processingId 由本类内部生成、调用方拿不到，导致前端查询永远 not_found）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoProcessingServiceImpl implements VideoProcessingService {

    private static final String PROCESSING_KEY_PREFIX = "upload:video:processing:";
    private static final Duration STATUS_TTL = Duration.ofHours(24);

    private final StringRedisTemplate stringRedisTemplate;
    private final FileStorageService fileStorageService;

    @Override
    @Async("taskExecutor")
    public void processVideoAsync(String processingId, String relativePath, String userNumber, Integer topicId) {
        try {
            updateStatus(processingId, "PROCESSING");

            long size = fileStorageService.size(relativePath);
            if (size <= 0) {
                throw new IllegalStateException("视频文件不存在或为空：" + relativePath);
            }

            // TODO 预留：FFmpeg 转码 / AI 视频分析。当前不做实际转码，避免引入额外依赖与显存开销。

            updateStatus(processingId, "COMPLETED");
            log.info("视频处理完成（占位实现）- path: {}, size: {} 字节, user: {}, topic: {}",
                    relativePath, size, userNumber, topicId);
        } catch (Exception e) {
            updateStatus(processingId, "FAILED");
            log.error("视频处理失败 - path: {}, processingId: {}", relativePath, processingId, e);
        }
    }

    @Override
    public String getProcessingStatus(String processingId) {
        if (processingId == null || processingId.trim().isEmpty()) {
            return "not_found";
        }
        String status = stringRedisTemplate.opsForValue().get(PROCESSING_KEY_PREFIX + processingId.trim());
        return status == null ? "not_found" : status;
    }

    private void updateStatus(String processingId, String status) {
        if (processingId == null || processingId.trim().isEmpty()) {
            return;
        }
        stringRedisTemplate.opsForValue().set(PROCESSING_KEY_PREFIX + processingId.trim(), status, STATUS_TTL);
    }
}
