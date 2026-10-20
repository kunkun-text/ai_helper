package com.ai_helper.ai_helper.Service;

import com.ai_helper.ai_helper.pojo.vo.ComponentStatusVo;

import java.util.List;

/**
 * 本机组件自检（2026-10-20）。
 *
 * <p>把「语音识别」与「图片识别」两组本机组件的状态统一暴露出来：
 * 有就显示已安装、没有就后台自动下载，并把进度/失败原因给到运维与前端。</p>
 */
public interface ComponentStatusService {

    /** 全部组件状态（顺序：whisper → OCR） */
    List<ComponentStatusVo> all();

    /** 启动后在日志里打印一段可读摘要（延迟执行，等自动安装线程先跑起来） */
    void logStartupSummary();
}
