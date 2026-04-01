package com.ai_helper.ai_helper.Service;

public interface VideoProcessingService {
    
    /**
     * 异步处理视频
     * @param videoUrl 视频URL
     * @param userId 用户ID
     * @param recordId 答辩记录ID（可选）
     * @param topicId 答辩题目ID（可选）
     */
    void processVideoAsync(String videoUrl, String userId, Long recordId, Long topicId);
    
    /**
     * 获取视频处理状态
     * @param processingId 处理ID
     * @return 处理状态
     */
    String getProcessingStatus(String processingId);
}