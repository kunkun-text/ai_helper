package com.ai_helper.ai_helper.mapper;

import com.ai_helper.ai_helper.pojo.entity.VoiceResponse;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 语音答辩归档（F11 · 2026-10-08）。表 {@code voice_responses}，建表语句见 docs/schema.sql（无需 DDL 变更）。
 */
@Mapper
public interface VoiceResponseMapper {

    /** 写入一轮录音归档；返回受影响行数（0 行表示失败，调用方负责回收已落盘的文件） */
    int insertVoiceResponse(VoiceResponse voiceResponse);

    /** 按答辩场次取逐轮录音，按 response_id 升序（即作答顺序） */
    List<VoiceResponse> selectByDefenseId(@Param("defenseId") Integer defenseId);
}
