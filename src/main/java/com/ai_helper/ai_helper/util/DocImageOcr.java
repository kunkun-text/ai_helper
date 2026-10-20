package com.ai_helper.ai_helper.util;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 文档内图片的文字识别（OCR，2026-10-20）。
 *
 * <p><b>为什么是「识别文字」而不是「转码后喂给模型」</b>：本项目模型固定为
 * {@code qwen2.5:3b-16k}（纯文本模型，没有视觉能力）。图片无论转成 PNG / JPEG / base64，
 * 模型都读不到；唯一能让图片内容进入评判的办法，就是把图里的**文字**识别出来变成文本。
 * 所以「转码」在这里的含义是：<b>任意可解码位图 → PNG（统一预处理）→ UTF-8 文本</b>。</p>
 *
 * <p><b>支持的图片格式</b>：JDK {@code ImageIO} 能解码的位图（png / jpg / gif / bmp / tiff）。
 * {@code emf / wmf / svg} 是矢量格式，JDK 无法栅格化，直接返回 null（调用方写入占位说明）。</p>
 *
 * <p><b>失败口径</b>：引擎缺失、图片损坏、超时、识别结果无意义 —— 一律返回 null，
 * <b>绝不抛异常</b>。图片是评分增强项，不能因为它让整份报告评析失败。</p>
 */
public final class DocImageOcr {

    /** 单张图片识别结果的字数上限（防止一张截图把 16k 上下文挤爆） */
    private static final int MAX_TEXT_CHARS = 1500;

    /** 宽度小于该值时放大，提升小字号截图的识别率 */
    private static final int MIN_WIDTH = 1000;

    /** 放大后的宽度上限，避免超大图吃内存 */
    private static final int MAX_WIDTH = 3200;

    private final String executable;

    private final int timeoutSeconds;

    public DocImageOcr(String executable, int timeoutSeconds) {
        this.executable = executable;
        this.timeoutSeconds = Math.max(5, timeoutSeconds);
    }

    /** OCR 引擎是否可用（可执行文件存在） */
    public boolean available() {
        return executable != null && !executable.isBlank()
                && Files.isRegularFile(Path.of(executable));
    }

    /**
     * 识别一张图片里的文字。
     *
     * @return 识别文本（可能为空串）；**无法识别时返回 null**
     */
    public String recognize(byte[] raw) {
        if (!available() || raw == null || raw.length == 0) {
            return null;
        }
        Path png = null;
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(raw));
            if (source == null) {
                return null;    // emf / wmf / svg：JDK 不认识
            }
            BufferedImage prepared = prepare(source);
            png = Files.createTempFile("doc-ocr-", ".png");
            if (!ImageIO.write(prepared, "png", png.toFile())) {
                return null;
            }

            List<String> command = new ArrayList<>();
            command.add(executable);
            command.add(png.toAbsolutePath().toString());
            command.add("stdout");
            command.add("-l");
            command.add("chi_sim+eng");
            command.add("--psm");
            command.add("6");

            Process process = new ProcessBuilder(command).start();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return null;
            }
            String output;
            try (InputStream in = process.getInputStream()) {
                output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            String text = clean(output);
            return isMeaningful(text) ? text : "";
        } catch (Exception e) {
            // 引擎缺失 / 图片损坏 / 进程异常：一律走降级
            return null;
        } finally {
            if (png != null) {
                try {
                    Files.deleteIfExists(png);
                } catch (IOException ignored) {
                    // 临时文件清理失败不影响结果
                }
            }
        }
    }

    /**
     * 统一预处理：白底重绘（透明 PNG 直接 OCR 会得到半透明黑块）→ 小图放大 → 灰度。
     * 输出统一为 PNG，这是 {@code tesseract} 最稳的输入形态。
     */
    private BufferedImage prepare(BufferedImage source) {
        int width = Math.max(1, source.getWidth());
        int height = Math.max(1, source.getHeight());
        double scale = 1.0;
        if (width < MIN_WIDTH) {
            scale = Math.min(2.0, (double) MAX_WIDTH / width);
            if (scale < 1.0) {
                scale = 1.0;
            }
        }
        int targetWidth = Math.max(1, (int) Math.round(width * scale));
        int targetHeight = Math.max(1, (int) Math.round(height * scale));

        BufferedImage canvas = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, targetWidth, targetHeight);
            g.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        } finally {
            g.dispose();
        }

        BufferedImage gray = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g2 = gray.createGraphics();
        try {
            g2.drawImage(canvas, 0, 0, null);
        } finally {
            g2.dispose();
        }
        return gray;
    }

    private String clean(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.replace("\r\n", "\n").replace('\r', '\n')
                .replaceAll("[ \\t]+\\n", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
        if (text.length() > MAX_TEXT_CHARS) {
            text = text.substring(0, MAX_TEXT_CHARS);
        }
        return text;
    }

    /**
     * 过滤「图表被识别成一堆符号」的噪声：可读字符（字母/数字/汉字）占比低于一半就判为无意义。
     */
    private boolean isMeaningful(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        int useful = 0;
        int total = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            total++;
            if (Character.isLetterOrDigit(c) || c >= 0x4E00) {
                useful++;
            }
        }
        return total > 0 && (double) useful / total >= 0.5;
    }
}
