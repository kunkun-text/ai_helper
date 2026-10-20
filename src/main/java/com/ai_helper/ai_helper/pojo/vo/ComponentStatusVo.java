package com.ai_helper.ai_helper.pojo.vo;

/**
 * 本机组件自检状态（2026-10-20）。
 *
 * <p>语音（whisper）与图片识别（OCR）都是「能自动补齐的本机组件」：
 * 有就直接用，没有就后台下载。前端/运维需要能一眼看出**当前到底装没装、
 * 正在装到哪一步、装失败的原因**，而不是去翻日志。</p>
 *
 * @param key        组件标识：{@code whisper} / {@code ocr}
 * @param name       展示名
 * @param installed  主能力是否已就绪
 * @param installing 是否正在后台安装/下载
 * @param percent    进度 0-100（未安装且未开始时为 0）
 * @param stage      当前阶段描述
 * @param location   实际安装目录或引擎位置（Windows 自带 OCR 时为说明文字）
 * @param message    人类可读结论：已安装 / 下载中 / 缺失原因
 */
public record ComponentStatusVo(
        String key,
        String name,
        boolean installed,
        boolean installing,
        int percent,
        String stage,
        String location,
        String message) {
}
