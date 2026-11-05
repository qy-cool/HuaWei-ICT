package cloide.server.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * 验证统一错误码与 HTTP 状态之间的认证契约。
 */
class ErrorCodeTests {

    /**
     * 验证认证相关错误码与项目需求保持一致。
     */
    @Test
    void authenticationErrorCodesShouldMatchApiContract() {
        assertEquals(40001, ErrorCode.INVALID_USERNAME_OR_PASSWORD.getCode());
        assertEquals(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_USERNAME_OR_PASSWORD.getHttpStatus());
        assertEquals(40100, ErrorCode.UNAUTHORIZED.getCode());
        assertEquals(HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED.getHttpStatus());
        assertEquals(40300, ErrorCode.FORBIDDEN.getCode());
        assertEquals(HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN.getHttpStatus());
        assertEquals(40400, ErrorCode.RESOURCE_NOT_FOUND.getCode());
        assertEquals(HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND.getHttpStatus());
        // MVP-10：RabbitMQ 不可达返回 503（存活但未就绪语义）。
        assertEquals(50301, ErrorCode.RABBITMQ_UNAVAILABLE.getCode());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.RABBITMQ_UNAVAILABLE.getHttpStatus());
    }
}
