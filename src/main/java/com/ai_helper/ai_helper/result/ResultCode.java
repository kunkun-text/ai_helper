package com.ai_helper.ai_helper.result;

/**
 * 统一响应码常量。
 *
 * <p>借鉴通用 Web 工程的统一错误码设计：成功固定为 {@link #SUCCESS}=1、普通业务失败为
 * {@link #FAIL}=0（**与历史完全一致，前端 {@code code === 1} 判定不受影响**），
 * 新增的 4xx/5xx 语义码用于把「参数错误 / 未授权 / 无权限 / 资源不存在」等场景显式化，
 * 便于前端按类别给出不同提示。</p>
 *
 * <p>兼容性说明：{@code Result.error(String)} 仍然产出 {@code code=0}，
 * 只有显式调用 {@code Result.error(int, String)} 时才会带上语义码。</p>
 */
public final class ResultCode {

    private ResultCode() {
    }

    /** 成功 */
    public static final int SUCCESS = 1;

    /** 业务失败（默认，保持历史语义） */
    public static final int FAIL = 0;

    /** 参数校验失败 */
    public static final int PARAM_ERROR = 400;

    /** 未登录 / token 失效 */
    public static final int UNAUTHORIZED = 401;

    /** 已登录但无权限 */
    public static final int FORBIDDEN = 403;

    /** 资源不存在 */
    public static final int NOT_FOUND = 404;

    /** 服务器内部错误 */
    public static final int SERVER_ERROR = 500;
}
