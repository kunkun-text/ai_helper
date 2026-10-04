package com.ai_helper.ai_helper.Service.Impl;

import com.ai_helper.ai_helper.Config.AppProperties;
import com.ai_helper.ai_helper.Service.DefenseRecordsService;
import com.ai_helper.ai_helper.Service.FileStorageService;
import com.ai_helper.ai_helper.Service.MediaFileService;
import com.ai_helper.ai_helper.exception.BusinessException;
import com.ai_helper.ai_helper.mapper.DefenseRecordsMapper;
import com.ai_helper.ai_helper.pojo.enums.MediaKind;
import com.ai_helper.ai_helper.util.UploadUtils;
import cn.hutool.core.util.IdUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 附件统一读写实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaFileServiceImpl implements MediaFileService {

    private final AppProperties appProperties;
    private final FileStorageService fileStorageService;
    private final DefenseRecordsMapper defenseRecordsMapper;
    private final DefenseRecordsService defenseRecordsService;

    @Override
    public Map<String, Object> saveWholeFile(String loginUserNumber, Integer topicId, MediaKind kind, MultipartFile file) {
        // 1. 先校验登录态与文件，再落盘 —— 原先「先传云端、后查用户」的写法会在用户不存在时留下孤儿文件
        String internalUserId = requireInternalUserId(loginUserNumber);
        if (topicId == null) {
            throw new BusinessException("请先选择对应的答辩题目");
        }
        validateFile(kind, file);

        Integer defenseId = defenseRecordsService.getOrCreateLatestDefenseRecord(topicId, loginUserNumber, true);
        if (defenseId == null) {
            throw new BusinessException("无法定位答辩记录，请先在该题目下开始答辩");
        }

        // 2. 落盘
        // 【F7 · 2026-10-05】命名规范化：生成「答辩报告_学号_原名_短随机.ext」这类安全可读的存储名，
        // 不再直接用原名（防目录穿越/重名覆盖），扩展名以白名单校验结果为准（不信前端传的临时路径）
        String storedName = buildSafeStoredName(kind, loginUserNumber, file.getOriginalFilename());
        String relativePath;
        try (InputStream in = file.getInputStream()) {
            relativePath = fileStorageService.save(kind.getSubDir(), storedName, in);
        } catch (Exception e) {
            log.error("附件落盘失败 - kind: {}, topicId: {}, user: {}", kind, topicId, loginUserNumber, e);
            throw new BusinessException("文件保存失败，请稍后重试", e);
        }

        String publicUrl = fileStorageService.toPublicUrl(relativePath);

        // 3. 写库；失败则回收刚落的盘，保证「要么都成、要么都不成」
        try {
            String oldUrl = readStoredUrl(internalUserId, topicId, kind);
            writeStoredUrl(defenseId, kind, publicUrl);
            if (!UploadUtils.isBlank(oldUrl) && !oldUrl.trim().equals(publicUrl)) {
                // 历史数据可能是阿里云 OSS 地址，delete 内部会识别并跳过
                fileStorageService.delete(oldUrl);
            }
        } catch (Exception e) {
            fileStorageService.delete(relativePath);
            log.error("附件写库失败，已回滚磁盘文件 - kind: {}, defenseId: {}", kind, defenseId, e);
            throw new BusinessException("附件信息保存失败，请重试");
        }

        log.info("附件已保存 - kind: {}, defenseId: {}, url: {}, 大小: {}",
                kind.getCode(), defenseId, publicUrl, UploadUtils.readableSize(file.getSize()));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("url", publicUrl);
        result.put("fileName", file.getOriginalFilename());
        result.put("fileSize", file.getSize());
        result.put("kind", kind.getCode());
        return result;
    }

    @Override
    public String getFileUrl(String loginUserNumber, Integer topicId, MediaKind kind) {
        String internalUserId = requireInternalUserId(loginUserNumber);
        if (topicId == null) {
            throw new BusinessException("请先选择对应的答辩题目");
        }
        return readStoredUrl(internalUserId, topicId, kind);
    }

    @Override
    public void deleteFile(String loginUserNumber, Integer topicId, MediaKind kind) {
        String internalUserId = requireInternalUserId(loginUserNumber);
        if (topicId == null) {
            throw new BusinessException("请先选择对应的答辩题目");
        }

        // 删除场景不允许「为删而建」记录
        Integer defenseId = defenseRecordsService.getOrCreateLatestDefenseRecord(topicId, loginUserNumber, false);
        if (defenseId == null) {
            throw new BusinessException("还没有上传过" + kind.getLabel() + "，无需删除");
        }

        String oldUrl = readStoredUrl(internalUserId, topicId, kind);
        if (UploadUtils.isBlank(oldUrl)) {
            throw new BusinessException("还没有上传过" + kind.getLabel() + "，无需删除");
        }

        clearStoredUrl(defenseId, kind);
        fileStorageService.delete(oldUrl);
        log.info("附件已删除 - kind: {}, defenseId: {}, url: {}", kind.getCode(), defenseId, oldUrl);
    }

    @Override
    public Map<String, Object> describePolicy(MediaKind kind) {
        AppProperties.FileRule rule = kind.ruleOf(appProperties);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("kind", kind.getCode());
        data.put("label", kind.getLabel());
        data.put("maxSize", rule.getMaxSize());
        data.put("maxSizeText", UploadUtils.readableSize(rule.getMaxSize()));
        data.put("allowedExts", rule.getAllowedExts());
        data.put("chunked", kind.isChunked());
        data.put("chunkSize", rule.getChunkSize());
        data.put("urlPrefix", appProperties.getStorage().getUrlPrefix());
        return data;
    }

    // ==================== 内部工具 ====================

    private String requireInternalUserId(String loginUserNumber) {
        if (UploadUtils.isBlank(loginUserNumber)) {
            throw new BusinessException("登录状态已失效，请重新登录");
        }
        String internalUserId = defenseRecordsMapper.getUserIdByUserNumber(loginUserNumber.trim());
        if (internalUserId == null) {
            throw new BusinessException("未找到对应的用户信息");
        }
        return internalUserId;
    }

    private void validateFile(MediaKind kind, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("请选择要上传的文件");
        }
        AppProperties.FileRule rule = kind.ruleOf(appProperties);
        if (rule.getAllowedExts() == null || rule.getAllowedExts().isEmpty()) {
            throw new BusinessException("服务端未配置可上传的文件格式");
        }

        String ext = UploadUtils.extensionOf(file.getOriginalFilename());
        if (!rule.getAllowedExts().contains(ext)) {
            throw new BusinessException("不支持的文件格式"
                    + (ext.isEmpty() ? "" : "（." + ext + "）")
                    + "，仅支持：" + String.join(" / ", rule.getAllowedExts()));
        }
        if (file.getSize() > rule.getMaxSize()) {
            throw new BusinessException(kind.getLabel() + "过大：" + UploadUtils.readableSize(file.getSize())
                    + "，上限为 " + UploadUtils.readableSize(rule.getMaxSize()));
        }

        // 【F7】文件头校验：扩展名可能被伪造（把 .exe 改名成 .pdf），按魔数做一层兜底
        try (InputStream head = file.getInputStream()) {
            byte[] magic = head.readNBytes(4);
            if ("pdf".equals(ext) && !isPdfMagic(magic)) {
                throw new BusinessException("文件内容与扩展名不符，请上传真实的 PDF 文件");
            }
            if (("docx".equals(ext) || "xlsx".equals(ext) || "pptx".equals(ext)) && !isZipMagic(magic)) {
                throw new BusinessException("文件内容与扩展名不符，请上传真实的 Office 文件");
            }
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            log.warn("文件头校验读取失败（放行由后续解析兜底） - file: {}", file.getOriginalFilename());
        }
    }

    /** PDF 魔数：%PDF */
    private boolean isPdfMagic(byte[] magic) {
        return magic != null && magic.length >= 4
                && magic[0] == 0x25 && magic[1] == 0x50 && magic[2] == 0x44 && magic[3] == 0x46;
    }

    /** Office Open XML（docx/xlsx/pptx）魔数：PK\x03\x04（zip 容器） */
    private boolean isZipMagic(byte[] magic) {
        return magic != null && magic.length >= 4
                && magic[0] == 0x50 && magic[1] == 0x4B
                && magic[2] == 0x03 && magic[3] == 0x04;
    }

    /**
     * 【F7】生成安全可读的存储名：{@code 答辩报告_学号_原名_短随机.ext}。
     *
     * <p>原名只保留字母/数字/中文与少量安全符号，长度截断；扩展名已由 {@link #validateFile}
     * 按白名单校验，这里只做小写化，杜绝目录穿越与双扩展名。</p>
     */
    private String buildSafeStoredName(MediaKind kind, String userNumber, String originalFileName) {
        String ext = UploadUtils.extensionOf(originalFileName);
        String base = originalFileName == null ? "" : originalFileName;
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        // 去掉路径分隔符等危险字符，只保留中英文、数字、下划线、连字符与空格
        base = base.replaceAll("[\\\\/:*?\"<>|\\r\\n]", "").trim();
        if (base.isEmpty()) {
            base = kind.getLabel();
        }
        if (base.length() > 40) {
            base = base.substring(0, 40);
        }
        String user = (userNumber == null ? "" : userNumber.trim().replaceAll("[^0-9A-Za-z_\\-]", ""));
        String random = IdUtil.simpleUUID().substring(0, 6);
        return kind.getLabel() + "_" + user + "_" + base + "_" + random
                + (ext.isEmpty() ? "" : "." + ext.toLowerCase());
    }

    private String readStoredUrl(String internalUserId, Integer topicId, MediaKind kind) {
        return kind == MediaKind.VIDEO
                ? defenseRecordsMapper.getVideoUrlByUserIdAndTopicId(internalUserId, topicId.longValue())
                : defenseRecordsMapper.getReportUrlByUserIdAndTopicId(internalUserId, topicId);
    }

    private void writeStoredUrl(Integer defenseId, MediaKind kind, String url) {
        if (kind == MediaKind.VIDEO) {
            defenseRecordsMapper.updateVideoUrlById(defenseId.longValue(), url);
        } else {
            defenseRecordsMapper.updateReportUrlById(defenseId.longValue(), url);
        }
    }

    private void clearStoredUrl(Integer defenseId, MediaKind kind) {
        if (kind == MediaKind.VIDEO) {
            defenseRecordsMapper.clearVideoUrlByDefenseId(defenseId.longValue());
        } else {
            defenseRecordsMapper.clearReportUrlByDefenseId(defenseId.longValue());
        }
    }
}
