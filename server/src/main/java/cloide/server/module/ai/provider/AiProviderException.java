package cloide.server.module.ai.provider;

import cloide.server.common.BusinessException;
import cloide.server.common.ErrorCode;

/** 表示模型供应商调用失败并携带稳定的 API 错误码。 */
public class AiProviderException extends BusinessException {
    public AiProviderException(ErrorCode errorCode) {
        super(errorCode);
    }

    public AiProviderException(ErrorCode errorCode, Throwable cause) {
        super(errorCode, cause);
    }
}
