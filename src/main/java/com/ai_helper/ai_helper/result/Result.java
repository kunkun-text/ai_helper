package com.ai_helper.ai_helper.result;

import lombok.Data;

import java.io.Serializable;

/**
 * 后端统一返回结果
 * @param <T>
 */
@Data
public class Result<T> implements Serializable {

    private Integer code; //编码：1成功，0和其它数字为失败
    private String msg; //错误信息
    private T data; //数据

    public static <T> Result<T> success() {
        Result<T> result = new Result<T>();
        result.code = 1;
        return result;
    }

    public static <T> Result<T> success(T object) {
        Result<T> result = new Result<T>();
        result.data = object;
        result.code = 1;
        return result;
    }

    public static <T> Result<T> error(String msg) {
        Result result = new Result();
        result.msg = msg;
        result.code = 0;
        return result;
    }

    /**
     * 带语义错误码的失败结果。
     *
     * <p>成功仍为 {@code code=1}、默认失败仍为 {@code code=0}，前端 {@code code === 1}
     * 判定不受影响；本重载只是把参数错误 / 未授权 / 无权限 / 资源不存在等语义显式化。</p>
     *
     * @see ResultCode
     */
    public static <T> Result<T> error(int code, String msg) {
        Result<T> result = new Result<T>();
        result.code = code;
        result.msg = msg;
        return result;
    }

}
