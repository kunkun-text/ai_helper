package com.ai_helper.ai_helper.Service.Impl;

import cn.hutool.core.util.IdUtil;
import com.ai_helper.ai_helper.Config.AppProperties;
import com.ai_helper.ai_helper.Service.ChunkUploadService;
import com.ai_helper.ai_helper.Service.DefenseRecordsService;
import com.ai_helper.ai_helper.Service.FileStorageService;
import com.ai_helper.ai_helper.Service.VideoAnalysisService;
import com.ai_helper.ai_helper.Service.VideoProcessingService;
import com.ai_helper.ai_helper.exception.BusinessException;
import com.ai_helper.ai_helper.mapper.DefenseRecordsMapper;
import com.ai_helper.ai_helper.util.UploadUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
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

    /** 【N71】清理扫描周期：6 小时；启动后先等 10 分钟（避开启动高峰，也让刚启动时的活会话先稳定） */
    private static final long CLEANUP_INTERVAL_MS = 6L * 60 * 60 * 1000;
    private static final long CLEANUP_INITIAL_DELAY_MS = 10L * 60 * 1000;
    /** 【N71】比会话寿命多留 2 小时余量：防边界误删（init 预分配后、写会话前的瞬间崩溃留下的孤儿，等下一轮再收） */
    private static final long ORPHAN_AGE_MARGIN_MS = 2L * 60 * 60 * 1000;

    private final AppProperties appProperties;
    private final FileStorageService fileStorageService;
    private final StringRedisTemplate stringRedisTemplate;
    private final DefenseRecordsMapper defenseRecordsMapper;
    private final DefenseRecordsService defenseRecordsService;
    private final VideoProcessingService videoProcessingService;
    private final VideoAnalysisService videoAnalysisService;

    @Override
    public Map<String, Object> init(String loginUserNumber, Integer topicId, Integer defenseId,
                                    String originalFileName, long fileSize) {
        if (loginUserNumber == null || loginUserNumber.trim().isEmpty()) {
            throw new BusinessException("登录状态已失效，请重新登录");
        }
        if (topicId == null) {
            throw new BusinessException("请先选择对应的答辩题目");
        }
        // 【VID-0 · 2026-10-20】答辩页上传必须绑定本场记录：
        // 原来只按 topicId 取「最新一条」，同一课题二次答辩时会写到别的场次上。
        Integer boundDefenseId = null;
        if (defenseId != null) {
            String ownerUserId = defenseRecordsMapper.getUserIdByUserNumber(loginUserNumber.trim());
            if (ownerUserId == null) {
                throw new BusinessException("未找到对应的用户信息");
            }
            if (defenseRecordsMapper.countOwnedDefenseRecord(defenseId, ownerUserId) <= 0) {
                throw new BusinessException("该答辩记录不属于当前账号，无法绑定视频");
            }
            boundDefenseId = defenseId;
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
        // 空串表示"未绑定"（Redis Hash 不接受 null 值）
        session.put("defenseId", boundDefenseId == null ? "" : String.valueOf(boundDefenseId));
        session.put("createdAt", String.valueOf(System.currentTimeMillis()));
        stringRedisTemplate.opsForHash().putAll(SESSION_KEY_PREFIX + uploadId, session);
        stringRedisTemplate.expire(SESSION_KEY_PREFIX + uploadId, SESSION_TTL);

        log.info("分片上传初始化 - uploadId: {}, user: {}, topicId: {}, defenseId: {}, 大小: {}, 分片数: {}",
                uploadId, loginUserNumber, topicId, boundDefenseId,
                UploadUtils.readableSize(fileSize), totalParts);

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
        // 【VID-0】init 时绑定过本场记录就写它（已校验归属）；没绑定才回落「最新一条」
        Integer defenseId = intOrNull(session, "defenseId");
        if (defenseId == null) {
            defenseId = defenseRecordsService.getOrCreateLatestDefenseRecord(topicId, user, true);
        }
        if (defenseId == null) {
            throw new BusinessException("无法定位答辩记录，请先在该题目下开始答辩");
        }

        // 库中存「对外 URL 路径」而非绝对地址，避免服务器 IP 变化后历史数据全部失效
        String publicUrl = fileStorageService.toPublicUrl(relativePath);

        // 替换前先记住旧文件，合并成功后再删除，避免「删了旧的、新的又没写进去」
        // 【2026-10-28】只按【本场次】取旧地址：换视频时只允许删自己这条的旧文件。
        // 旧实现用 getLatestVideoUrlByUserAndTopic（跨场次"最新一条"），
        // 一旦这次写的是新建场次，取到的就是上一场的 URL → 上一场视频文件被误删。
        String oldUrl = defenseRecordsMapper.getVideoUrlByDefenseId(defenseId);

        defenseRecordsMapper.updateVideoUrlById(defenseId.longValue(), publicUrl);

        if (oldUrl != null && !oldUrl.trim().isEmpty() && !oldUrl.trim().equals(publicUrl)) {
            // 历史数据可能是阿里云 OSS 地址，delete 内部会识别并跳过
            fileStorageService.delete(oldUrl);
        }

        clearSession(uploadId);

        String processingId = IdUtil.simpleUUID();
        videoProcessingService.processVideoAsync(processingId, relativePath, user, topicId);

        // 【2026-10-28】上传完成即自动提交评分（用户口径：学生传完后台就开始打分）
        autoTriggerVideoAnalysis(defenseId);

        log.info("视频上传完成 - uploadId: {}, defenseId: {}, url: {}, 大小: {}",
                uploadId, defenseId, publicUrl, UploadUtils.readableSize(fileSize));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("videoUrl", publicUrl);
        result.put("processingId", processingId);
        result.put("fileSize", fileSize);
        result.put("totalParts", totalParts);
        return result;
    }

    /**
     * 【2026-10-28】上传完成后自动提交视频评分任务。
     *
     * <p><b>为什么 try/catch 全包</b>：评分只是"锦上添花"，上传成功必须如实返回。
     * 组件未就绪、开关关闭、同一场正在评分（任务锁被占）都只记日志，
     * 教师端仍可手动点「AI 评分」重试。</p>
     *
     * <p>评分本身是异步提交（单线程池），这里不会阻塞上传响应。</p>
     */
    private void autoTriggerVideoAnalysis(Integer defenseId) {
        if (defenseId == null) {
            return;
        }
        AppProperties.VideoAnalysis cfg = appProperties.getVideoAnalysis();
        if (cfg == null || !cfg.isAutoAnalyzeOnUpload()) {
            return;
        }
        try {
            Map<String, Object> status = videoAnalysisService.trigger(defenseId, false);
            log.info("视频上传完成，已自动提交评分任务 - defenseId: {}, stage: {}",
                    defenseId, status == null ? "" : status.get("stage"));
        } catch (Exception e) {
            log.warn("自动提交视频评分失败（不影响上传结果，教师可手动重试） - defenseId: {}, 原因: {}",
                    defenseId, e.getMessage());
        }
    }

    @Override
    public void abort(String loginUserNumber, String uploadId) {
        Map<Object, Object> session = requireSession(loginUserNumber, uploadId);
        fileStorageService.delete(stringValue(session, "relativePath"));
        clearSession(uploadId);
        log.info("已放弃上传 - uploadId: {}, user: {}", uploadId, loginUserNumber);
    }

    /**
     * 【N71 · 2026-10-15】定时清理孤儿预分配文件。
     *
     * <p>背景：{@code init} 按声明大小预分配磁盘文件（视频上限 500MB），只有 abort/complete 会删它；
     * 用户中途直接放弃（不调 abort）时，Redis 会话 24h 过期后磁盘文件永久残留，
     * 反复 init 即可以 500MB/次的速率占满磁盘。判定三条同时满足才删，宁可漏删不可误删：</p>
     *
     * <ol>
     *   <li>文件 mtime 早于「会话寿命 + 余量」——活会话的文件必有新分片写入，mtime 必新；</li>
     *   <li>不属于任何存活的上传会话（SCAN 会话 key，不用 KEYS 阻塞 Redis）；</li>
     *   <li>不在库中任何 video_url 引用集合里——complete 之后它就是正式视频，绝不能当孤儿删。</li>
     * </ol>
     */
    @Scheduled(initialDelay = CLEANUP_INITIAL_DELAY_MS, fixedDelay = CLEANUP_INTERVAL_MS)
    public void cleanupOrphanPreallocFiles() {
        try {
            long orphanBefore = System.currentTimeMillis() - SESSION_TTL.toMillis() - ORPHAN_AGE_MARGIN_MS;
            // ② 存活会话引用的文件集合
            Set<String> activePaths = scanActiveUploadPaths();
            // ③ 库中已引用的视频路径集合（complete 之后就是正式视频，绝不能当孤儿删）
            Set<String> protectedPaths = new HashSet<>();
            List<String> urls = defenseRecordsMapper.selectAllVideoUrls();
            if (urls != null) {
                for (String url : urls) {
                    String relative = fileStorageService.toRelativePath(url);
                    if (relative != null && !relative.isEmpty()) {
                        protectedPaths.add(relative);
                    }
                }
            }

            int removed = 0;
            for (String path : fileStorageService.listFiles(VIDEO_SUB_DIR)) {
                if (activePaths.contains(path) || protectedPaths.contains(path)) {
                    continue;
                }
                long mtime = fileStorageService.lastModified(path);
                if (mtime < 0 || mtime >= orphanBefore) {
                    // mtime 读不到时视为不可判定，宁可漏删
                    continue;
                }
                if (fileStorageService.delete(path)) {
                    removed++;
                    log.info("已清理孤儿预分配文件 - {}", path);
                }
            }
            if (removed > 0) {
                log.info("孤儿预分配文件清理完成 - 共清理 {} 个", removed);
            }
        } catch (Exception e) {
            // 定时任务绝不外抛异常
            log.error("孤儿预分配文件清理失败（下轮再试）", e);
        }
    }

    /** 【N71】SCAN 出存活上传会话引用的 relativePath 集合（判定②用；不用 KEYS，避免阻塞 Redis） */
    private Set<String> scanActiveUploadPaths() {
        ScanOptions options = ScanOptions.scanOptions().match(SESSION_KEY_PREFIX + "*").count(200).build();
        List<String> keys = stringRedisTemplate.execute((RedisCallback<List<String>>) connection -> {
            List<String> found = new ArrayList<>();
            try (Cursor<byte[]> cursor = connection.scan(options)) {
                while (cursor.hasNext()) {
                    found.add(new String(cursor.next(), StandardCharsets.UTF_8));
                }
            }
            return found;
        });
        Set<String> paths = new HashSet<>();
        if (keys != null) {
            for (String key : keys) {
                Map<Object, Object> session = stringRedisTemplate.opsForHash().entries(key);
                String path = session.get("relativePath") == null ? "" : String.valueOf(session.get("relativePath"));
                if (!path.isEmpty()) {
                    paths.add(path);
                }
            }
        }
        return paths;
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

    /** 可空整数：空串/缺失返回 null，脏数据按缺失处理（不能抛异常，否则老会话续传会直接失败） */
    private Integer intOrNull(Map<Object, Object> session, String key) {
        String raw = stringValue(session, key).trim();
        if (raw.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
