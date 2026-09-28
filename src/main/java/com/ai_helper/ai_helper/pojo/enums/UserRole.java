package com.ai_helper.ai_helper.pojo.enums;

/**
 * 用户角色编码常量。
 *
 * <p>与 {@code users.role} 枚举（student / teacher）保持一致，供鉴权、注册登录与业务判断复用，
 * 避免在拦截器、Controller、Service 各处重复写死字符串字面量。</p>
 */
public final class UserRole {

    /** 学生 */
    public static final String STUDENT = "student";

    /** 教师 */
    public static final String TEACHER = "teacher";

    private UserRole() {
    }
}
