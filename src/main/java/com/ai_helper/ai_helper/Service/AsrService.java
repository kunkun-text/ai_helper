package com.ai_helper.ai_helper.Service;

/**
 * 语音识别（ASR）：把学生的一段录音转成文字。
 *
 * <p><b>为什么做在后端</b>：微信「同声传译」插件已验证**个人主体的小程序无法添加**，
 * 因此改用"小程序原生录音（{@code wx.getRecorderManager}，不需要任何插件）→ 上传音频 → 后端识别"。
 * 识别引擎为本机离线的 whisper.cpp，不联网、不依赖小程序主体资质。</p>
 *
 * <p>识别出的文字会回填到前端输入框，学生**可以修改后再发送** ——
 * 这样专业术语（Hadoop / MapReduce / AQI 等）万一识别错，也不会直接影响得分。</p>
 */
public interface AsrService {

    /**
     * 识别一段音频。
     *
     * @param audio  音频字节
     * @param format 音频扩展名（mp3 / wav / aac / m4a），用于落成临时文件交给引擎
     * @return 识别出的文字（可能为空串，表示没听清）
     * @throws com.ai_helper.ai_helper.exception.BusinessException 引擎不可用、超时、识别失败等
     */
    String transcribe(byte[] audio, String format);

    /** 识别引擎是否就绪（未安装时应让前端立刻看到明确提示，而不是让用户干等） */
    boolean available();

    /** 引擎不可用的原因（用于日志与前端提示） */
    String unavailableReason();

    /**
     * 是否正在**首次自动下载**语音组件（引擎/模型/ffmpeg）。
     *
     * <p>下载期间前端应提示「组件准备中，稍候重试」，而不是直接报"不可用"，
     * 否则学生会误以为功能坏了。默认 false（不支持自动安装的实现无需实现本方法）。</p>
     */
    default boolean installing() {
        return false;
    }

    /** 自动下载进度（0-100）；未在下载时返回 0 */
    default int installProgress() {
        return 0;
    }
}
