package com.ai_helper.ai_helper.pojo.entity;


import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;


/**
* 
* @TableName defense_topics
*/
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DefenseTopics implements Serializable {

    /**
    * 
    */

    private Integer topicId;
    /**
    * 
    */

    private Integer teacherId;
    /**
    * 
    */
    private String topicName;
    /**
    * 
    */

    private String topicDescription;


    private String defenseTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private LocalDateTime createdAt;
    /**
    * 
    */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private LocalDateTime updatedAt;

}
