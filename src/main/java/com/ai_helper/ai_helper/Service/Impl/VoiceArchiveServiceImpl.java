package com.ai_helper.ai_helper.Service.Impl;

import com.ai_helper.ai_helper.Service.DefenseRecordsService;
import com.ai_helper.ai_helper.Service.FileStorageService;
import com.ai_helper.ai_helper.Service.VoiceArchiveService;
import com.ai_helper.ai_helper.mapper.DefenseRecordsMapper;
import com.ai_helper.ai_helper.mapper.VoiceResponseMapper;
import com.ai_helper.ai_helper.pojo.entity.VoiceResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * 语音录音归档实现（F11 · 2026-10-08）。落盘走现有 {@link FileStorageService}，回放走现有 {@code /files/**} 映射。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VoiceArchiveServiceImpl implements VoiceArchiveService {

    /** 归档子目录：{@code /files/voice/xxx.mp3}，与 app.storage.url-prefix 拼出对外可访问地址 */
    private static final String SUB_DIR = "voice";

    /** 题目文本入库上限（防止异常长文本撑爆 TEXT 与日志） */
    private static final int MAX_QUESTION_LENGTH = 1000;

    /** ASR 原文入库上限 */
    private static final int MAX_RESPONSE_TEXT_LENGTH = 5000;

    private final FileStorageService fileStorageService;
    private final VoiceResponseMapper voiceResponseMapper;
    private final DefenseRecordsService defenseRecordsService;
    private final DefenseRecordsMapper defenseRecordsMapper;

    @Override
    public String archive(String loginUserNumber, Integer topicId, Integer questionId,
                          String question, String responseText, byte[] audio, String ext) {
        if (audio == null || audio.length == 0) {
            return "";
        }
        if (loginUserNumber == null || loginUserNumber.trim().isEmpty() || topicId == null) {
            log.debug("跳过语音归档：缺少登录态或 topicId");
            return "";
        }
        String userNumber = loginUserNumber.trim();
        String relativePath = null;
        try {
            // 1. 定位当前进行中的答辩场次（不新建：已有场次才会归档，避免凭空造出脏记录）
            Integer defenseId = defenseRecordsService.getOrCreateLatestDefenseRecord(topicId, userNumber, false);
            if (defenseId == null) {
                log.info("跳过语音归档：该课题下没有进行中的答辩场次 - topicId: {}, user: {}", topicId, userNumber);
                return "";
            }

            // 2. 越权兜底：确认这场答辩确实属于当前登录用户（身份只信登录态）
            String internalUserId = defenseRecordsMapper.getUserIdByUserNumber(userNumber);
            if (internalUserId == null
                    || defenseRecordsMapper.countOwnedDefenseRecord(defenseId, internalUserId) <= 0) {
                log.warn("语音归档越权拦截 - defenseId: {}, 登录用户: {}", defenseId, userNumber);
                return "";
            }

            // 3. 落盘（文件名由存储层生成 UUID 形式，扩展名已由 Controller 白名单校验）
            String safeExt = (ext == null || ext.isBlank()) ? "mp3" : ext.trim().toLowerCase();
            String storedName = fileStorageService.generateFileName("answer." + safeExt);
            relativePath = fileStorageService.save(SUB_DIR, storedName, new ByteArrayInputStream(audio));
            String publicUrl = fileStorageService.toPublicUrl(relativePath);

            // 4. 写库；失败则回收刚落的盘，保证「要么都成、要么都不成」
            VoiceResponse record = new VoiceResponse();
            record.setDefenseId(defenseId);
            record.setQuestionId(questionId);
            record.setQuestion(truncate(question == null || question.isBlank() ? "（未记录题目）" : question, MAX_QUESTION_LENGTH));
            record.setResponseText(truncate(responseText, MAX_RESPONSE_TEXT_LENGTH));
            record.setResponseAudioUrl(publicUrl);
            record.setResponseTime(LocalDateTime.now());

            int rows = voiceResponseMapper.insertVoiceResponse(record);
            if (rows <= 0) {
                fileStorageService.delete(relativePath);
                log.warn("语音归档写库未生效，已回收录音文件 - defenseId: {}, 路径: {}", defenseId, relativePath);
                return "";
            }
            log.info("语音录音已归档 - defenseId: {}, questionId: {}, url: {}, 大小: {} 字节",
                    defenseId, questionId, publicUrl, audio.length);
            return publicUrl;
        } catch (Exception e) {
            // 归档失败不影响识别与作答：删掉可能残留的文件后返回空串
            if (relativePath != null) {
                try {
                    fileStorageService.delete(relativePath);
                } catch (Exception ignore) {
                    log.warn("语音归档回滚失败，可能残留孤儿文件 - 路径: {}", relativePath);
                }
            }
            log.warn("语音归档失败（不影响识别与作答） - topicId: {}, user: {}", topicId, userNumber, e);
            return "";
        }
    }

    @Override
    public List<VoiceResponse> listForStudent(String loginUserNumber, Integer defenseId) {
        if (loginUserNumber == null || loginUserNumber.trim().isEmpty() || defenseId == null) {
            return Collections.emptyList();
        }
        String userNumber = loginUserNumber.trim();
        try {
            String internalUserId = defenseRecordsMapper.getUserIdByUserNumber(userNumber);
            if (internalUserId == null
                    || defenseRecordsMapper.countOwnedDefenseRecord(defenseId, internalUserId) <= 0) {
                log.warn("语音回放越权拦截 - defenseId: {}, 登录用户: {}", defenseId, userNumber);
                return Collections.emptyList();
            }
            return voiceResponseMapper.selectByDefenseId(defenseId);
        } catch (Exception e) {
            log.warn("查询语音回放记录失败 - defenseId: {}, user: {}", defenseId, userNumber, e);
            return Collections.emptyList();
        }
    }

    @Override
    public List<VoiceResponse> listByDefenseId(Integer defenseId) {
        if (defenseId == null) {
            return Collections.emptyList();
        }
        return voiceResponseMapper.selectByDefenseId(defenseId);
    }

    private String truncate(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        return text.length() > maxLength ? text.substring(0, maxLength) : text;
    }
}
