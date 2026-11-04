package cloide.server.websocket;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import cloide.server.common.BusinessException;
import cloide.server.common.ErrorCode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 验证终端 WebSocket 协议的方向、标识、尺寸和二进制载荷边界。
 */
class TerminalProtocolValidatorTests {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 浏览器可发送合法 terminal.open。 */
    @Test
    void monitorOpenShouldAcceptValidPayload() {
        TerminalMessage message = message("terminal.open", payload()
                .put("server_id", 1)
                .put("cols", TerminalProtocolValidator.MAX_COLUMNS)
                .put("rows", TerminalProtocolValidator.MAX_ROWS));

        assertDoesNotThrow(() -> TerminalProtocolValidator.validateMonitorMessage(message));
    }

    /** 浏览器不能发送仅限 Agent 的 terminal.output。 */
    @Test
    void monitorShouldRejectAgentOnlyMessageType() {
        TerminalMessage message = message("terminal.output", payload()
                .put("server_id", 1)
                .put("session_id", UUID.randomUUID().toString())
                .put("data", Base64.getEncoder().encodeToString(new byte[] {1})));

        assertInvalid(() -> TerminalProtocolValidator.validateMonitorMessage(message));
    }

    /** Agent 输出必须绑定 server_id 和 session_id。 */
    @Test
    void agentOutputShouldRequireServerAndSession() {
        TerminalMessage message = message("terminal.output", payload()
                .put("session_id", UUID.randomUUID().toString())
                .put("data", Base64.getEncoder().encodeToString(new byte[] {1})));

        assertInvalid(() -> TerminalProtocolValidator.validateAgentMessage(message));
    }

    /** Base64 解码后的数据在 16 KiB 边界内允许。 */
    @Test
    void terminalInputShouldAcceptDataAtLimit() {
        byte[] data = new byte[TerminalProtocolValidator.MAX_DATA_BYTES];
        TerminalMessage message = message("terminal.input", payload()
                .put("session_id", UUID.randomUUID().toString())
                .put("data", Base64.getEncoder().encodeToString(data)));

        assertDoesNotThrow(() -> TerminalProtocolValidator.validateMonitorMessage(message));
    }

    /** Base64 解码后的数据超过 16 KiB 必须拒绝。 */
    @Test
    void terminalInputShouldRejectDataOverLimit() {
        byte[] data = new byte[TerminalProtocolValidator.MAX_DATA_BYTES + 1];
        TerminalMessage message = message("terminal.input", payload()
                .put("session_id", UUID.randomUUID().toString())
                .put("data", Base64.getEncoder().encodeToString(data)));

        assertInvalid(() -> TerminalProtocolValidator.validateMonitorMessage(message));
    }

    /** 终端尺寸超过协议边界必须拒绝。 */
    @Test
    void terminalResizeShouldRejectDimensionsOverLimit() {
        TerminalMessage message = message("terminal.resize", payload()
                .put("session_id", UUID.randomUUID().toString())
                .put("cols", TerminalProtocolValidator.MAX_COLUMNS + 1)
                .put("rows", TerminalProtocolValidator.MAX_ROWS));

        assertInvalid(() -> TerminalProtocolValidator.validateMonitorMessage(message));
    }

    /** Java 下发给 Agent 的 terminal.open 必须附带 server_id 和 session_id。 */
    @Test
    void serverOpenShouldRequireSessionId() {
        TerminalMessage message = message("terminal.open", payload()
                .put("server_id", 1)
                .put("cols", 80)
                .put("rows", 24));

        assertInvalid(() -> TerminalProtocolValidator.validateServerToAgentMessage(message));
    }

    /** 协议时间必须是 UTC ISO-8601。 */
    @Test
    void terminalMessageShouldRejectNonUtcTimestamp() {
        TerminalMessage message = new TerminalMessage("terminal.close", UUID.randomUUID().toString(),
                "2026-07-26T12:00:00+08:00", payload().put("session_id", UUID.randomUUID().toString()));

        assertInvalid(() -> TerminalProtocolValidator.validateMonitorMessage(message));
    }

    /** 构造使用当前 UTC 格式的终端消息。 */
    private TerminalMessage message(String type, com.fasterxml.jackson.databind.node.ObjectNode payload) {
        return new TerminalMessage(type, UUID.randomUUID().toString(),
                OffsetDateTime.now(ZoneOffset.UTC).toString(), payload);
    }

    /** 创建空 JSON 载荷。 */
    private com.fasterxml.jackson.databind.node.ObjectNode payload() {
        return OBJECT_MAPPER.createObjectNode();
    }

    /** 断言协议错误映射为稳定终端错误码。 */
    private void assertInvalid(org.junit.jupiter.api.function.Executable executable) {
        BusinessException exception = assertThrows(BusinessException.class, executable);
        assertEquals(ErrorCode.TERMINAL_INVALID_PAYLOAD, exception.getErrorCode());
    }
}
