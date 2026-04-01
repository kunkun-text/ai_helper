package com.ai_helper.ai_helper.pojo.query;

import lombok.Data;
import lombok.Setter;
import org.springframework.ai.tool.annotation.ToolParam;

@Data
@Setter
public class TextQuery {

    @ToolParam(required = false,description = "视频中ppt转文字内容")
    private String VideoPptWords;

    @ToolParam(required = false,description = "视频中音频转文字内容")
    private String VideoWords;

}
