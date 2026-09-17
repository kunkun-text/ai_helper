package com.ai_helper.ai_helper.pojo.enums;

import com.ai_helper.ai_helper.Config.AppProperties;
import lombok.Getter;

/**
 * 可上传的附件类型。
 *
 * <p>把「子目录 / 配置名 / 中文名 / 上传方式」集中在一处，
 * 后续新增文件类型（如 PPT、其他附件）只需在此追加枚举项 + 补一段 yml 配置。</p>
 */
@Getter
public enum MediaKind {

    /** 答辩视频（大文件，支持分片上传） */
    VIDEO("videos", "video", "答辩视频", true),

    /** 答辩报告（文档，单次直传） */
    REPORT("reports", "report", "答辩报告", false);

    /** 存储子目录名 */
    private final String subDir;

    /** 配置项中使用的名称（app.upload.{code}） */
    private final String code;

    /** 面向用户的中文名称，用于提示文案 */
    private final String label;

    /** 是否走分片上传 */
    private final boolean chunked;

    MediaKind(String subDir, String code, String label, boolean chunked) {
        this.subDir = subDir;
        this.code = code;
        this.label = label;
        this.chunked = chunked;
    }

    /**
     * 取本类型对应的上传规则（大小上限 / 允许扩展名 / 分片大小）。
     */
    public AppProperties.FileRule ruleOf(AppProperties appProperties) {
        return this == VIDEO
                ? appProperties.getUpload().getVideo()
                : appProperties.getUpload().getReport();
    }
}
