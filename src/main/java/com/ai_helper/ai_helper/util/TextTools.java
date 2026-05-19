package com.ai_helper.ai_helper.util;

import com.ai_helper.ai_helper.Service.DefenseRecordsService;
import com.ai_helper.ai_helper.pojo.query.TextQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class TextTools {

    private final DefenseRecordsService defenseRecordsService;

    @Tool(description = "根据答辩题目 ID 查询该题目的视频内容文字信息，包括视频中 PPT 的文字内容和演讲的语音转文字内容。当用户提问涉及视频内容、演讲内容、PPT 内容、项目展示等时使用此工具获取详细信息")
    public TextQuery getVideoContentByTopicId(@ToolParam(description = "答辩题目 ID，用于查询对应的视频文字内容", required = true) Integer topicId) {
        System.out.println("topicId: " + topicId);
        
        TextQuery result = defenseRecordsService.getDefenseWordsRecords(topicId);
        
        if (result != null) {
            int pptWordsLength = result.getVideoPptWords() != null ? result.getVideoPptWords().length() : 0;
            int videoWordsLength = result.getVideoWords() != null ? result.getVideoWords().length() : 0;
            
            System.out.println("✅ 查询成功");
            System.out.println("   - PPT 文字长度：" + pptWordsLength);
            System.out.println("   - 语音文字长度：" + videoWordsLength);
            
            if (pptWordsLength > 0 || videoWordsLength > 0) {
                System.out.println("📊 找到相关视频内容，将提供给大模型参考");
            } else {
                System.out.println("⚠️ 视频内容为空或暂无文字转换结果");
            }
        } else {
            System.out.println("❌ 查询结果为空，该 topicId 可能没有对应的视频记录");
        }
        
        return result;
    }

}
