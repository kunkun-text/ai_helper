package com.ai_helper.ai_helper.Service;

public interface VideoProcessingService {
    /**
     * 异步处理视频文件
     * @param videoUrl 视频URL
     * @param userId 用户ID
     * @param recordId 答辩记录ID（如果已存在）
     */
    void processVideoAsync(String videoUrl, String userId, Long recordId);
    
    /**
     * 获取视频处理状态
     * @param processingId 处理任务ID
     * @return 处理状态
     */
    String getProcessingStatus(String processingId);
}