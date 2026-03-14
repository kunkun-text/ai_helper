package com.ai_helper.ai_helper.mapper;

import com.ai_helper.ai_helper.pojo.dto.UserDto;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PasswordResetMapper {

    UserDto selectByEmail(String email);

    int updatePassword(@Param("userNumber") String userNumber,@Param("password") String password);
}
