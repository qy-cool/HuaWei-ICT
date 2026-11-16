package cloide.server.common;

import org.springframework.http.HttpStatus;

/**
 * 全项目错误码枚举，由 API 契约统一定义。
 */
public enum ErrorCode {

    SUCCESS(0, "success", HttpStatus.OK),
    BAD_REQUEST(40000, "bad request", HttpStatus.BAD_REQUEST),
    INVALID_USERNAME_OR_PASSWORD(40001, "invalid username or password", HttpStatus.BAD_REQUEST),
    INVALID_REQUEST_PARAMETER(40002, "invalid request parameter", HttpStatus.BAD_REQUEST),
    TERMINAL_INVALID_PAYLOAD(40003, "terminal invalid payload", HttpStatus.BAD_REQUEST),
    UNAUTHORIZED(40100, "unauthorized", HttpStatus.UNAUTHORIZED),
    FORBIDDEN(40300, "forbidden", HttpStatus.FORBIDDEN),
    // 首管理员未初始化时注册未携带一次性初始化令牌（批次 8），
    // 仅在 auth_bootstrap_state.admin_initialized=0 的注册路径产生。
    AUTH_BOOTSTRAP_REQUIRED(40310, "bootstrap token required", HttpStatus.FORBIDDEN),
    // 首管理员未初始化时注册携带的初始化令牌与服务器签发值不匹配（批次 8），
    // 同一错误码也覆盖「状态行尚无令牌密文」的防御性拒绝（fail-closed）。
    AUTH_BOOTSTRAP_TOKEN_INVALID(40311, "bootstrap token invalid", HttpStatus.FORBIDDEN),
    TERMINAL_ACCESS_DENIED(40302, "terminal access denied", HttpStatus.FORBIDDEN),
    RESOURCE_NOT_FOUND(40400, "resource not found", HttpStatus.NOT_FOUND),
    TERMINAL_SESSION_NOT_FOUND(40403, "terminal session not found", HttpStatus.NOT_FOUND),
    RESOURCE_CONFLICT(40900, "resource conflict", HttpStatus.CONFLICT),
    GITHUB_APP_NOT_CONFIGURED(50307, "GitHub App configuration unavailable", HttpStatus.SERVICE_UNAVAILABLE),
    GITHUB_API_UNAVAILABLE(50308, "GitHub request not confirmed; retry the same operation", HttpStatus.SERVICE_UNAVAILABLE),
    SSH_HOST_KEY_NOT_CONFIRMED(40901, "ssh host key not confirmed", HttpStatus.CONFLICT),
    SSH_HOST_KEY_MISMATCH(40902, "ssh host key mismatch", HttpStatus.CONFLICT),
    TERMINAL_SESSION_STATE_CONFLICT(40903, "terminal session state conflict", HttpStatus.CONFLICT),
    TERMINAL_AGENT_OFFLINE(40904, "terminal agent offline", HttpStatus.CONFLICT),
    SSH_TARGET_FORBIDDEN(40301, "ssh target forbidden", HttpStatus.FORBIDDEN),
    SSH_CONNECTION_LIMIT_REACHED(42900, "ssh connection limit reached", HttpStatus.TOO_MANY_REQUESTS),
    AGENT_CONNECTION_LIMIT_REACHED(42901, "agent connection limit reached", HttpStatus.TOO_MANY_REQUESTS),
    AGENT_MESSAGE_RATE_LIMIT_REACHED(42902, "agent message rate limit reached", HttpStatus.TOO_MANY_REQUESTS),
    TERMINAL_SESSION_LIMIT_REACHED(42903, "terminal session limit reached", HttpStatus.TOO_MANY_REQUESTS),
    TERMINAL_MESSAGE_LIMIT_REACHED(42904, "terminal message limit reached", HttpStatus.TOO_MANY_REQUESTS),
    LOGIN_RATE_LIMIT_REACHED(42905, "login rate limit reached", HttpStatus.TOO_MANY_REQUESTS),
    AI_RATE_LIMIT_REACHED(42906, "AI rate limit reached", HttpStatus.TOO_MANY_REQUESTS),
    COMMAND_RATE_LIMIT_REACHED(42907, "command rate limit reached", HttpStatus.TOO_MANY_REQUESTS),
    INTERNAL_SERVER_ERROR(50000, "internal server error", HttpStatus.INTERNAL_SERVER_ERROR),
    DATABASE_ERROR(50001, "database error", HttpStatus.INTERNAL_SERVER_ERROR),
    SSH_CONNECTION_FAILED(50002, "ssh connection failed", HttpStatus.BAD_GATEWAY),
    SSH_AUTHENTICATION_FAILED(50003, "ssh authentication failed", HttpStatus.BAD_GATEWAY),
    RABBITMQ_UNAVAILABLE(50301, "rabbitmq unavailable", HttpStatus.SERVICE_UNAVAILABLE),
    REDIS_UNAVAILABLE(50302, "redis unavailable", HttpStatus.SERVICE_UNAVAILABLE),
    SSH_CONNECTION_TIMEOUT(50400, "ssh connection timeout", HttpStatus.GATEWAY_TIMEOUT),
    AI_PROVIDER_UNAVAILABLE(50303, "AI provider unavailable", HttpStatus.BAD_GATEWAY),
    AI_DISABLED_OR_REDACTION_FAILED(50304, "AI diagnosis unavailable", HttpStatus.SERVICE_UNAVAILABLE),
    AI_RESPONSE_INVALID(50305, "AI provider response invalid", HttpStatus.BAD_GATEWAY),
    AI_PROVIDER_ENDPOINT_BLOCKED(50306, "AI provider endpoint blocked", HttpStatus.BAD_GATEWAY),
    AI_PROVIDER_TIMEOUT(50401, "AI provider timeout", HttpStatus.GATEWAY_TIMEOUT),
    COMMAND_PARAM_INVALID(40004, "command param invalid", HttpStatus.BAD_REQUEST),
    COMMAND_RUN_NOT_FOUND(40404, "command run not found", HttpStatus.NOT_FOUND),
    COMMAND_RUN_STATE_CONFLICT(40905, "command run state conflict", HttpStatus.CONFLICT),
    COMMAND_AGENT_OFFLINE(40906, "command agent offline", HttpStatus.CONFLICT),
    // A1 仓库身份：ref 不在允许清单或格式非法（如通配符、非 refs/heads 前缀）。
    SOURCE_REF_NOT_ALLOWED(40005, "source ref not allowed", HttpStatus.BAD_REQUEST),
    // A1 仓库身份：期望提交与现场解析结果不符（分支前移），data 携带当前实际 SHA 供客户端重读。
    SOURCE_COMMIT_MOVED(40907, "expected commit sha does not match the current ref", HttpStatus.CONFLICT),
    // A1 仓库身份：同名幂等键携带不同业务参数，或策略版本乐观锁冲突。
    SOURCE_REQUEST_CONFLICT(40908, "source request conflict", HttpStatus.CONFLICT),
    // A1 仓库身份：GitHub 上分支或提交不存在（404）；安装权限被撤（403）不可重试，按 RESOURCE_CONFLICT 反馈。
    SOURCE_REF_NOT_FOUND(40405, "source ref not found on remote", HttpStatus.NOT_FOUND),
    COMMAND_EXECUTION_TIMEOUT(50402, "command execution timeout", HttpStatus.GATEWAY_TIMEOUT);

    private final int code;

    private final String message;

    private final HttpStatus httpStatus;

    /**
     * 构造错误码枚举项。
     *
     * @param code 数字错误码
     * @param message 默认错误消息
     * @param httpStatus 对应的 HTTP 状态码
     */
    ErrorCode(int code, String message, HttpStatus httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    /**
     * 获取数字错误码。
     *
     * @return 数字错误码
     */
    public int getCode() {
        return code;
    }

    /**
     * 获取默认错误消息。
     *
     * @return 默认错误消息
     */
    public String getMessage() {
        return message;
    }

    /**
     * 获取对应的 HTTP 状态码。
     *
     * @return HTTP 状态码
     */
    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
