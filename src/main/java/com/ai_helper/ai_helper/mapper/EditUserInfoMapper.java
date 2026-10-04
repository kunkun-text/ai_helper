package com.ai_helper.ai_helper.mapper;

import com.ai_helper.ai_helper.pojo.dto.EditUserInfoDto;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface EditUserInfoMapper {

    void editUserInfo(EditUserInfoDto editUserInfo);

    /** 【B2】学号是否已被其他账号占用 */
    int countByUserNumberExcludingId(@Param("userNumber") String userNumber, @Param("id") String id);

    /** 【B2】邮箱是否已被其他账号占用 */
    int countByEmailExcludingId(@Param("email") String email, @Param("id") String id);
}
