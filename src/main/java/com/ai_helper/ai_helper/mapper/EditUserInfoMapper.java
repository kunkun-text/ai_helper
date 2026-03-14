package com.ai_helper.ai_helper.mapper;

import com.ai_helper.ai_helper.pojo.dto.EditUserInfoDto;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface EditUserInfoMapper {

    void editUserInfo(EditUserInfoDto editUserInfo);
}
