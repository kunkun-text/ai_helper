package com.ai_helper.ai_helper.mapper;

import com.ai_helper.ai_helper.pojo.dto.UserDto;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface RegisterMapper {


    void register(UserDto userDto);

    UserDto selectByUserNumber(@Param("userNumber") String userNumber);

}
