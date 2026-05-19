package com.ai_helper.ai_helper.Service.Impl;

import com.ai_helper.ai_helper.Service.AliyunOssService;
import com.ai_helper.ai_helper.Service.VideoProcessingService;
import com.ai_helper.ai_helper.mapper.DefenseRecordsMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class VideoProcessingServiceImpl implements VideoProcessingService {
    
    // 模拟处理状态存储（生产环境应使用Redis）
    private static final ConcurrentHashMap<String, String> processingStatus = new ConcurrentHashMap<>();
    
    @Resource
    private DefenseRecordsMapper defenseRecordsMapper;
    
    @Resource
    private AliyunOssService aliyunOssService;
    
    @Override
    @Async("taskExecutor")
    public void processVideoAsync(String videoUrl, String userNumber, Long recordId, Long topicId) {
        String processingId = "proc_" + System.currentTimeMillis() + "_" + userNumber;
        processingStatus.put(processingId, "processing");
        
        try {
            log.info("开始异步处理视频：{}, userNumber: {}, recordId: {}, topicId: {}", videoUrl, userNumber, recordId, topicId);
            
            // 1. 视频转码（这里只是模拟，实际可以调用 FFmpeg 或其他转码服务）
            processingStatus.put(processingId, "transcoding");
            
            // 2. AI 视频分析（模拟）
            processingStatus.put(processingId, "ai_analysis");

            //根据 userId 查询 defenseRecords 中的 userId
            String userId = defenseRecordsMapper.getUserIdByUserNumber(userNumber);
            
            // 3. 更新或创建数据库记录
            if (topicId != null && userNumber != null) {
                try {
                    // 先查旧 video_url（用于删除旧文件）
                    String oldVideoUrl = userId != null
                        ? defenseRecordsMapper.getVideoUrlByUserIdAndTopicId(userId, topicId)
                        : null;

                    // 原子化 upsert（线程安全）：不存在则插入，存在则更新
                    if (userId != null) {
                        defenseRecordsMapper.upsertVideoUrl(userId, topicId, videoUrl);
                        log.info("视频 URL 保存成功 - userId: {}, topicId: {}", userId, topicId);
                    } else {
                        log.warn("未找到用户: {}", userNumber);
                    }

                    // 删除阿里云原视频（异步删除，不阻塞主流程）
                    deleteOldVideoAsync(oldVideoUrl, videoUrl);
                } catch (Exception e) {
                    log.error("数据库操作失败 - userId: {}, topicId: {}, videoUrl: {}", userId, topicId, videoUrl, e);
                    throw e; // 重新抛出异常，确保处理状态正确设置为失败
                }
            } else if (recordId != null) {
                
                // 更新 videoUrl
                defenseRecordsMapper.updateVideoUrlById(recordId, videoUrl);
                log.info("视频处理完成，更新记录 ID: {}", recordId);

            } else {
                log.warn("无法保存视频记录：缺少必要的参数 (userId: {}, topicId: {}, recordId: {})", userId, topicId, recordId);
            }
            
            processingStatus.put(processingId, "completed");
            log.info("视频处理完成：{}", videoUrl);
            
        } catch (Exception e) {
            log.error("视频处理失败：{}", videoUrl, e);
            processingStatus.put(processingId, "failed");
        }
    }
    
    /**
     * 异步删除旧视频文件
     * @param oldVideoUrl 旧视频 URL
     * @param newVideoUrl 新视频 URL（用于校验，避免误删）
     */
    private void deleteOldVideoAsync(String oldVideoUrl, String newVideoUrl) {
        if (oldVideoUrl != null && !oldVideoUrl.isEmpty() && 
            !oldVideoUrl.equals(newVideoUrl)) {
            try {
                // 异步删除，不阻塞主流程
                CompletableFuture.runAsync(() -> {
                    boolean success = aliyunOssService.deleteFile(oldVideoUrl);
                    if (success) {
                        log.info("旧视频删除成功：{}", oldVideoUrl);
                    } else {
                        log.warn("旧视频删除失败或无需删除：{}", oldVideoUrl);
                    }
                });
            } catch (Exception e) {
                log.error("异步删除旧视频失败：{}", oldVideoUrl, e);
                // 不抛出异常，避免影响主流程
            }
        }
    }
    
    @Override
    public String getProcessingStatus(String processingId) {
        return processingStatus.getOrDefault(processingId, "not_found");
    }
}
