package com.ai_helper.ai_helper.Service;

public interface VideoProcessingService {

    /**
     * 异步处理视频（转码 / AI 分析等，当前为占位实现）。
     *
     * @param processingId 由调用方生成的进度查询 ID，前端凭它轮询处理状态
     * @param relativePath 视频的相对存储路径，如 videos/xxx.mp4
     * @param userNumber   学号
     * @param topicId      答辩题目 ID
     */
    void processVideoAsync(String processingId, String relativePath, String userNumber, Integer topicId);

    /**
     * 获取视频处理状态。
     *
     * @return PENDING / PROCESSING / COMPLETED / FAILED，查不到返回 not_found
     */
    String getProcessingStatus(String processingId);
}
