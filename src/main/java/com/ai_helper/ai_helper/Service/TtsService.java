package com.ai_helper.ai_helper.Service;

/**
 * 文本转语音（TTS）。
 *
 * <p><b>为什么做在后端</b>：语音答辩最初计划用「微信同声传译」插件（前端直接合成与识别），
 * 但已验证**个人主体的小程序无法添加该插件**（插件管理与服务市场均搜不到）。
 * 因此改为由后端合成语音，小程序只负责播放 —— 好处是不再依赖小程序主体资质，
 * 换任何小程序都能用；代价是后端要多一个合成步骤（约 1~2 秒，且结果会缓存）。</p>
 *
 * <p>与 {@link FileStorageService} 的关系：合成结果按文本内容做缓存，
 * 落在存储根目录的 {@code tts/} 子目录下，对外通过 {@code /files/tts/xxx.wav} 访问 ——
 * 复用现有的静态资源映射，不新增存储通道。</p>
 */
public interface TtsService {

    /**
     * 把文本合成为语音，返回**可直接播放的对外 URL**。
     *
     * <p>同一段文本只会真正合成一次（结果按内容哈希缓存）。</p>
     *
     * @param text 待合成文本（超长会被截断）
     * @return 音频 URL，如 {@code /files/tts/xxxx.wav}；**合成失败返回空串** ——
     *         调用方据此静默跳过播报即可，不得因此中断答辩流程
     */
    String synthesizeToUrl(String text);
}
