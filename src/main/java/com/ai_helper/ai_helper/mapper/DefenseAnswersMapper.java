
package com.ai_helper.ai_helper.mapper;

import com.ai_helper.ai_helper.pojo.entity.DefenseAnswers;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DefenseAnswersMapper {

    int insertAnswer(DefenseAnswers answer);
}
