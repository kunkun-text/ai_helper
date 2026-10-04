package com.ai_helper.ai_helper.constant;

import java.util.List;

/**
 * 语音识别（whisper.cpp）相关文件名常量。
 *
 * <p><b>为什么要集中定义</b>：引擎/模型/ffmpeg 的「查找」（{@code WhisperAsrServiceImpl}）
 * 与「自动下载」（{@code VoiceComponentInstaller}）必须使用同一套文件名。
 * 一旦两边的名字对不上，就会出「明明下载完了却提示未就绪」这类极难排查的问题。</p>
 */
public final class VoiceConstants {

    private VoiceConstants() {
    }

    /** 引擎可执行文件名（新版为 whisper-cli.exe，旧版为 main.exe） */
    public static final List<String> ENGINE_EXE_NAMES = List.of("whisper-cli.exe", "main.exe");

    /** 模型文件名，按优先级排列（优先中文效果与速度平衡的 small） */
    public static final List<String> MODEL_FILE_NAMES = List.of(
            "ggml-small.bin", "ggml-base.bin", "ggml-medium.bin", "ggml-tiny.bin");

    /** ffmpeg 可执行文件名（把 webm / mp3 / aac 统一转成 16kHz wav） */
    public static final String FFMPEG_EXE_NAME = "ffmpeg.exe";
}
