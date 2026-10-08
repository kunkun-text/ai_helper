package com.ai_helper.ai_helper.Service;

import com.ai_helper.ai_helper.pojo.entity.VoiceResponse;

import java.util.List;

/**
 * 语音答辩录音归档与回放（F11 · 2026-10-08）。
 *
 * <p>目标（见 `docs/P0-需求-语音答辩-2026-09-28.md` 第八、九节 V2）：学生每轮语音作答的**原录音**要能归档落盘，
 * 事后学生本人与教师都能回放。归档落在既有的 {@code voice_responses} 表 + 现有 {@code /files/**} 静态映射上，
 * 不引入新的存储层。</p>
 *
 * <p><b>身份口径</b>：一律以登录态学号/工号为唯一依据；归档前会校验该场答辩确实属于当前登录用户，
 * 防止拿别人的 topicId 往别人场次里塞录音。</p>
 */
public interface VoiceArchiveService {

    /**
     * 归档一轮语音作答：把录音落盘 + 写 {@code voice_responses}。
     *
     * <p><b>尽力而为，不抛异常</b>：归档只是「附加价值」，不能因为它失败而让学生的识别/作答流程报错。
     * 失败时返回空串并记日志，调用方按「无回放地址」处理。</p>
     *
     * @param loginUserNumber 登录态学号/工号
     * @param topicId         课题 ID（用于定位当前进行中的答辩场次，前端本来就持有，无需新增字段）
     * @param questionId      预设题 ID；追问传 null
     * @param question        AI 本轮问的题（语音播报的那段文本）
     * @param responseText    ASR 原始识别文字
     * @param audio           录音字节
     * @param ext             扩展名（已由调用方白名单校验）
     * @return 可回放的 URL（形如 {@code /files/voice/xxx.mp3}）；归档失败返回空串
     */
    String archive(String loginUserNumber, Integer topicId, Integer questionId,
                   String question, String responseText, byte[] audio, String ext);

    /**
     * 学生端回放：取指定答辩场次的逐轮录音，按作答顺序返回。
     *
     * <p><b>归属校验</b>：校验该场答辩属于当前登录用户，不是自己的场次一律返回空列表
     * （不抛异常、不泄露存在性），避免拿 defenseId 遍历他人录音。</p>
     */
    List<VoiceResponse> listForStudent(String loginUserNumber, Integer defenseId);

    /**
     * 教师端回放：取指定答辩场次的逐轮录音（调用方需保证已通过角色校验 {@code @RequireRole(TEACHER)}）。
     */
    List<VoiceResponse> listByDefenseId(Integer defenseId);
}
