package com.ai_helper.ai_helper.Service;

import java.util.Map;

/**
 * 分片上传服务（视频等大文件）。
 *
 * <p>会话状态存放于 Redis，有效期 24 小时；文件本体由 {@link FileStorageService} 落盘。</p>
 *
 * <p>典型调用顺序：init →（并发/串行）savePart × N → complete；任意阶段失败可 abort。
 * 失败重试时先调 status 拿到已成功的分片号，跳过已传分片即可续传。</p>
 */
public interface ChunkUploadService {

    /**
     * 初始化一次分片上传。
     *
     * @param loginUserNumber  登录态中的学号（不信任前端传参）
     * @param topicId          所属答辩题目
     * @param originalFileName 原始文件名（用于取扩展名与展示）
     * @param fileSize         文件总字节数
     * @return uploadId / chunkSize / totalParts / receivedParts
     */
    Map<String, Object> init(String loginUserNumber, Integer topicId, String originalFileName, long fileSize);

    /**
     * 上传一个分片。
     *
     * @param partNumber 分片序号，从 1 开始
     * @param data       分片原始字节
     * @return receivedParts / totalParts / receivedCount
     */
    Map<String, Object> savePart(String loginUserNumber, String uploadId, int partNumber, byte[] data);

    /**
     * 查询上传进度（用于失败重试时续传）。
     *
     * @return receivedParts / totalParts / fileSize / completed
     */
    Map<String, Object> status(String loginUserNumber, String uploadId);

    /**
     * 完成上传：校验分片完整性 → 写入答辩记录 → 清理旧文件 → 触发后续处理。
     *
     * @return videoUrl（相对存储路径）/ processingId / fileSize
     */
    Map<String, Object> complete(String loginUserNumber, String uploadId);

    /**
     * 放弃上传：删除已落盘的半成品文件与会话。
     */
    void abort(String loginUserNumber, String uploadId);
}
