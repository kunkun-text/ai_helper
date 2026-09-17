package com.ai_helper.ai_helper.Service.Impl;

import cn.hutool.core.util.IdUtil;
import com.ai_helper.ai_helper.Config.AppProperties;
import com.ai_helper.ai_helper.Service.ChunkUploadService;
import com.ai_helper.ai_helper.Service.DefenseRecordsService;
import com.ai_helper.ai_helper.Service.FileStorageService;
import com.ai_helper.ai_helper.Service.VideoProcessingService;
import com.ai_helper.ai_helper.exception.BusinessException;
import com.ai_helper.ai_helper.mapper.DefenseRecordsMapper;
import com.ai_helper.ai_helper.util.UploadUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 分片上传服务实现。
 *
 * <p>关键点：分片按其真实字节区间写入同一个目标文件的对应偏移量，
 * 不再出现「同一份完整文件被重复上传多次、合并后体积翻倍且内容损坏」的问题。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChunkUploadServiceImpl implements ChunkUploadService {

    private static final String VIDEO_SUB_DIR = "videos";
    private static final String SESSION_KEY_PREFIX = "upload:video:session:";
    private static final String PARTS_KEY_PREFIX = "upload:video:parts:";
    private static final Duration SESSION_TTL = Duration.ofHours(24);
    private static final long DEFAULT_CHUNK_SIZE = 5L * 1024 * 1024;
    /** uploadId 合法性校验，避免被构造成奇怪的 Redis key */
    private static final String UPLOAD_ID_PATTERN = "^[a-zA-Z0-9\\-]{8,64}$";

    private final AppProperties appProperties;
    private final FileStorageService fileStorageService;
    private final StringRedisTemplate stringRedisTemplate;
    private final DefenseRecordsMapper defenseRecordsMapper;
    private final DefenseRecordsService defenseRecordsService;
    private final VideoProcessingService videoProcessingService;

    @Override
    public Map<String, Object> init(String loginUserNumber, Integer topicId, String originalFileName, long fileSize) {
        if (loginUserNumber == null || loginUserNumber.trim().isEmpty()) {
            throw new BusinessException("登录状态已失效，请重新登录");
        }
        if (topicId == null) {
            throw new BusinessException("请先选择对应的答辩题目");
        }

        AppProperties.FileRule rule = appProperties.getUpload().getVideo();
        String ext = UploadUtils.extensionOf(originalFileName);
        if (rule.getAllowedExts() == null || rule.getAllowedExts().isEmpty()) {
            throw new BusinessException("服务端未配置可上传的视频格式");
        }
        if (!rule.getAllowedExts().contains(ext)) {
            throw new BusinessException("不支持的视频格式"
                    + (ext.isEmpty() ? "" : "（." + ext + "）")
                    + "，仅支持：" + String.join(" / ", rule.getAllowedExts()));
        }
        if (fileSize <= 0) {
            throw new BusinessException("文件大小异常，无法上传");
        }
        if (fileSize > rule.getMaxSize()) {
            throw new BusinessException("视频过大：" + UploadUtils.readableSize(fileSize)
                    + "，上限为 " + UploadUtils.readableSize(rule.getMaxSize()));
        }

        long chunkSize = rule.getChunkSize() > 0 ? rule.getChunkSize() : DEFAULT_CHUNK_SIZE;
        int totalParts = (int) ((fileSize + chunkSize - 1) / chunkSize);

        String uploadId = IdUtil.simpleUUID();
        String storedFileName = fileStorageService.generateFileName(originalFileName);
        String relativePath;
        try {
            relativePath = fileStorageService.createWithSize(VIDEO_SUB_DIR, storedFileName, fileSize);
        } catch (Exception e) {
            log.error("预分配视频文件失败 - originalFileName: {}, fileSize: {}", originalFileName, fileSize, e);
            throw new BusinessException("服务器创建文件失败，请稍后重试", e);
        }

        Map<String, String> session = new LinkedHashMap<>();
        session.put("uploadId", uploadId);
        session.put("userNumber", loginUserNumber.trim());
        session.put("topicId", String.valueOf(topicId));
        session.put("originalFileName", originalFileName == null ? "" : originalFileName);
        session.put("storedFileName", storedFileName);
        session.put("relativePath", relativePath);
        session.put("fileSize", String.valueOf(fileSize));
        session.put("chunkSize", String.valueOf(chunkSize));
        session.put("totalParts", String.valueOf(totalParts));
        session.put("createdAt", String.valueOf(System.currentTimeMillis()));
        stringRedisTemplate.opsForHash().putAll(SESSION_KEY_PREFIX + uploadId, session);
        stringRedisTemplate.expire(SESSION_KEY_PREFIX + uploadId, SESSION_TTL);

        log.info("分片上传初始化 - uploadId: {}, user: {}, topicId: {}, 大小: {}, 分片数: {}",
                uploadId, loginUserNumber, topicId, UploadUtils.readableSize(fileSize), totalParts);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("uploadId", uploadId);
        result.put("chunkSize", chunkSize);
        result.put("totalParts", totalParts);
        result.put("receivedParts", Collections.emptyList());
        return result;
    }

    @Override
    public Map<String, Object> savePart(String loginUserNumber, String uploadId, int partNumber, byte[] data) {
        Map<Object, Object> session = requireSession(loginUserNumber, uploadId);

        int totalParts = intValue(session, "totalParts");
        long chunkSize = longValue(session, "chunkSize");
        long fileSize = longValue(session, "fileSize");

        if (partNumber < 1 || partNumber > totalParts) {
            throw new BusinessException("分片序号越界：" + partNumber + "（应在 1 ~ " + totalParts + " 之间）");
        }

        long offset = (long) (partNumber - 1) * chunkSize;
        long expectedLength = Math.min(chunkSize, fileSize - offset);
        int actualLength = data == null ? 0 : data.length;
        if (actualLength != expectedLength) {
            throw new BusinessException("分片大小不匹配：第 " + partNumber + " 片应为 "
                    + expectedLength + " 字节，实际收到 " + actualLength + " 字节");
        }

        String relativePath = stringValue(session, "relativePath");
        try {
            fileStorageService.writeAt(relativePath, offset, data);
        } catch (Exception e) {
            log.error("写入分片失败 - uploadId: {}, partNumber: {}, offset: {}", uploadId, partNumber, offset, e);
            throw new BusinessException("分片写入失败，请重试该分片", e);
        }

        String partsKey = PARTS_KEY_PREFIX + uploadId;
        stringRedisTemplate.opsForSet().add(partsKey, String.valueOf(partNumber));
        stringRedisTemplate.expire(partsKey, SESSION_TTL);

        List<Integer> received = receivedParts(uploadId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("receivedParts", received);
        result.put("receivedCount", received.size());
        result.put("totalParts", totalParts);
        return result;
    }

    @Override
    public Map<String, Object> status(String loginUserNumber, String uploadId) {
        Map<Object, Object> session = requireSession(loginUserNumber, uploadId);
        List<Integer> received = receivedParts(uploadId);
        int totalParts = intValue(session, "totalParts");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("uploadId", uploadId);
        result.put("receivedParts", received);
        result.put("receivedCount", received.size());
        result.put("totalParts", totalParts);
        result.put("fileSize", longValue(session, "fileSize"));
        result.put("allPartsReceived", received.size() >= totalParts);
        return result;
    }

    @Override
    public Map<String, Object> complete(String loginUserNumber, String uploadId) {
        Map<Object, Object> session = requireSession(loginUserNumber, uploadId);

        int totalParts = intValue(session, "totalParts");
        long fileSize = longValue(session, "fileSize");
        String relativePath = stringValue(session, "relativePath");
        Integer topicId = (int) longValue(session, "topicId");
        String user = stringValue(session, "userNumber");

        List<Integer> received = receivedParts(uploadId);
        if (received.size() < totalParts) {
            List<Integer> missing = new ArrayList<>();
            for (int i = 1; i <= totalParts; i++) {
                if (!received.contains(i)) {
                    missing.add(i);
                }
            }
            String preview = missing.size() > 10
                    ? missing.subList(0, 10) + " 等"
                    : String.valueOf(missing);
            throw new BusinessException("还有 " + missing.size() + " 个分片未上传完成，无法合并：第 " + preview + " 片");
        }

        long actualSize = fileStorageService.size(relativePath);
        if (actualSize != fileSize) {
            // 大小不符说明有分片缺失或写入异常，直接丢弃这次上传，避免把损坏文件写进库
            fileStorageService.delete(relativePath);
            clearSession(uploadId);
            throw new BusinessException("文件校验失败（应为 " + fileSize + " 字节，实际 "
                    + actualSize + " 字节），本次上传已作废，请重新上传");
        }

        String internalUserId = defenseRecordsMapper.getUserIdByUserNumber(user);
        if (internalUserId == null) {
            throw new BusinessException("未找到对应的用户信息");
        }
        Integer defenseId = defenseRecordsService.getOrCreateLatestDefenseRecord(topicId, user, true);
        if (defenseId == null) {
            throw new BusinessException("无法定位答辩记录，请先在该题目下开始答辩");
        }

        // 库中存「对外 URL 路径」而非绝对地址，避免服务器 IP 变化后历史数据全部失效
        String publicUrl = fileStorageService.toPublicUrl(relativePath);

        // 替换前先记住旧文件，合并成功后再删除，避免「删了旧的、新的又没写进去」
        String oldUrl = defenseRecordsMapper.getVideoUrlByUserIdAndTopicId(internalUserId, topicId.longValue());

        defenseRecordsMapper.updateVideoUrlById(defenseId.longValue(), publicUrl);

        if (oldUrl != null && !oldUrl.trim().isEmpty() && !oldUrl.trim().equals(publicUrl)) {
            // 历史数据可能是阿里云 OSS 地址，delete 内部会识别并跳过
            fileStorageService.delete(oldUrl);
        }

        clearSession(uploadId);

        String processingId = IdUtil.simpleUUID();
        videoProcessingService.processVideoAsync(processingId, relativePath, user, topicId);

        log.info("视频上传完成 - uploadId: {}, defenseId: {}, url: {}, 大小: {}",
                uploadId, defenseId, publicUrl, UploadUtils.readableSize(fileSize));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("videoUrl", publicUrl);
        result.put("processingId", processingId);
        result.put("fileSize", fileSize);
        result.put("totalParts", totalParts);
        return result;
    }

    @Override
    public void abort(String loginUserNumber, String uploadId) {
        Map<Object, Object> session = requireSession(loginUserNumber, uploadId);
        fileStorageService.delete(stringValue(session, "relativePath"));
        clearSession(uploadId);
        log.info("已放弃上传 - uploadId: {}, user: {}", uploadId, loginUserNumber);
    }

    // ==================== 内部工具 ====================

    /**
     * 读取并校验上传会话：必须存在，且属于当前登录用户（防止拿别人的 uploadId 续传/完成）。
     */
    private Map<Object, Object> requireSession(String loginUserNumber, String uploadId) {
        if (uploadId == null || !uploadId.matches(UPLOAD_ID_PATTERN)) {
            throw new BusinessException("上传标识非法");
        }
        Map<Object, Object> session = stringRedisTemplate.opsForHash().entries(SESSION_KEY_PREFIX + uploadId);
        if (session == null || session.isEmpty()) {
            throw new BusinessException("上传会话不存在或已过期（有效期 24 小时），请重新上传");
        }
        String owner = stringValue(session, "userNumber");
        if (loginUserNumber == null || !owner.equals(loginUserNumber.trim())) {
            log.warn("越权上传操作被拦截 - uploadId: {}, 会话所有者: {}, 当前登录: {}", uploadId, owner, loginUserNumber);
            throw new BusinessException("无权操作该上传任务");
        }
        return session;
    }

    private void clearSession(String uploadId) {
        stringRedisTemplate.delete(SESSION_KEY_PREFIX + uploadId);
        stringRedisTemplate.delete(PARTS_KEY_PREFIX + uploadId);
    }

    private List<Integer> receivedParts(String uploadId) {
        Set<String> raw = stringRedisTemplate.opsForSet().members(PARTS_KEY_PREFIX + uploadId);
        if (raw == null || raw.isEmpty()) {
            return Collections.emptyList();
        }
        Set<Integer> parts = new LinkedHashSet<>();
        for (String item : raw) {
            try {
                parts.add(Integer.parseInt(item));
            } catch (NumberFormatException ignored) {
                // 脏数据直接跳过
            }
        }
        List<Integer> sorted = new ArrayList<>(parts);
        Collections.sort(sorted);
        return sorted;
    }

    private String stringValue(Map<Object, Object> session, String key) {
        Object value = session.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private long longValue(Map<Object, Object> session, String key) {
        try {
            return Long.parseLong(stringValue(session, key));
        } catch (NumberFormatException e) {
            throw new BusinessException("上传会话数据异常，请重新上传");
        }
    }

    private int intValue(Map<Object, Object> session, String key) {
        return (int) longValue(session, key);
    }
}
