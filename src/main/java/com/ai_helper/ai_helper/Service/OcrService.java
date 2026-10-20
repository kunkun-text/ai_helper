package com.ai_helper.ai_helper.Service;

import com.ai_helper.ai_helper.pojo.vo.ComponentStatusVo;

/**
 * 文档图片文字识别（OCR）能力入口。
 *
 * <p>与语音识别同款思路：引擎缺失时由后端在启动后后台自动补齐，
 * 补齐失败只降级（图片内容不参与评分），不影响上传与文字评析。</p>
 */
public interface OcrService {

    /** 引擎与中文语言包是否都已就绪 */
    boolean available();

    /**
     * 识别一张图片里的文字。
     *
     * @param raw       原始图片字节
     * @param extension 扩展名（不含点），Windows OCR 需要用它决定临时文件后缀
     * @return 识别文本（可能为空串）；不可用时返回 null
     */
    String recognize(byte[] raw, String extension);

    /** 组件缺失时后台自动安装（幂等，重复调用直接忽略） */
    void installIfMissingAsync();

    /** 不可用原因（用于日志与教师端提示，可用时返回空串） */
    String unavailableReason();

    /** 组件状态快照（供 /api/system/components 与启动日志） */
    ComponentStatusVo status();
}
