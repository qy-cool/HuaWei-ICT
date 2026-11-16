package cloide.server.common;

/**
 * 业务异常，携带稳定的 API 错误码；可选携带供客户端处理冲突的业务数据。
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final transient Object data;

    /**
     * 创建一个业务异常，根据错误码设置异常消息。
     *
     * @param errorCode 业务错误码，决定 HTTP 状态和响应文案
     */
    public BusinessException(ErrorCode errorCode) {
        this(errorCode, null, null);
    }

    /**
     * 创建一个业务异常，携带供客户端继续处理所需的最小业务数据（如分支前移后的实际 SHA）。
     *
     * @param errorCode 业务错误码，决定 HTTP 状态和响应文案
     * @param data      冲突上下文数据，序列化进错误响应的 data 字段；不得包含敏感值
     */
    public BusinessException(ErrorCode errorCode, Object data) {
        this(errorCode, null, data);
    }

    /**
     * 创建一个业务异常，携带原始底层异常 cause，用于异常翻译场景。
     *
     * @param errorCode 业务错误码，决定 HTTP 状态和响应文案
     * @param cause     原始异常（如 DuplicateKeyException），保留技术原因便于排查
     */
    public BusinessException(ErrorCode errorCode, Throwable cause) {
        this(errorCode, cause, null);
    }

    private BusinessException(ErrorCode errorCode, Throwable cause, Object data) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
        this.data = data;
    }

    /**
     * 获取异常携带的业务错误码。
     *
     * @return 业务错误码，供 GlobalExceptionHandler 决定响应状态和文案
     */
    public ErrorCode getErrorCode() {
        return errorCode;
    }

    /**
     * 获取冲突上下文数据；null 表示无附加数据。
     *
     * @return 序列化进错误响应 data 字段的最小业务数据
     */
    public Object getData() {
        return data;
    }
}
