package com.ai_helper.ai_helper.util;

/**
 * 登录 token 在 Redis 中的值格式：{@code userNumber|role}。
 *
 * <p>写入方 {@code RegisterServiceImpl#login}、读取方 {@code AuthInterceptor}
 * 共用本类，避免「两端各写一份格式、改一处漏一处」。</p>
 *
 * <p><b>兼容历史数据</b>：早期 token 只存了 userNumber、没有角色。
 * {@link #decode} 遇到这种旧格式会把角色解析为 {@code null}，
 * 调用方按「角色未知」处理——只要求登录的接口照常放行，
 * 要求角色的接口会提示重新登录（旧 token 需要重登一次才能用教师端）。</p>
 */
public final class LoginTokenValue {

    /** 登录 token 在 Redis 中的键前缀，后接 token 本身 */
    public static final String TOKEN_KEY_PREFIX = "login:token:";

    /** userNumber 与 role 之间的分隔符（userNumber 本身不含该字符） */
    private static final String SEPARATOR = "|";

    private LoginTokenValue() {
    }

    /** 拼出 token 对应的 Redis 键（写入端与校验端复用，避免前缀各写一份） */
    public static String tokenKey(String token) {
        return TOKEN_KEY_PREFIX + token;
    }

    /**
     * 编码为 Redis 存储值。
     *
     * @param userNumber 学号 / 工号
     * @param role       角色（student / teacher），为空时退化为仅存 userNumber
     */
    public static String encode(String userNumber, String role) {
        if (userNumber == null) {
            return null;
        }
        return (role == null || role.isEmpty()) ? userNumber : userNumber + SEPARATOR + role;
    }

    /**
     * 解析 Redis 存储值。
     *
     * @param value Redis 中取出的原始值
     * @return 长度固定为 2 的数组：[userNumber, role]；解析不出角色时 role 为 {@code null}
     */
    public static String[] decode(String value) {
        if (value == null) {
            return new String[]{null, null};
        }
        int idx = value.indexOf(SEPARATOR);
        if (idx < 0) {
            return new String[]{value, null};
        }
        String userNumber = value.substring(0, idx);
        String role = value.substring(idx + SEPARATOR.length());
        return new String[]{userNumber, role.isEmpty() ? null : role};
    }
}
