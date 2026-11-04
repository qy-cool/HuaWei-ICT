package cloide.server.module.terminal.enums;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * 验证终端会话持久化状态使用固定值且能拒绝未知数据库状态。
 */
class TerminalSessionStatusTests {

    /** 所有持久化状态值必须可逆解析。 */
    @Test
    void valuesShouldRoundTrip() {
        for (TerminalSessionStatus status : TerminalSessionStatus.values()) {
            assertEquals(status, TerminalSessionStatus.fromValue(status.value()));
        }
    }

    /** 未知值不能被默认为任意合法状态。 */
    @Test
    void unknownValueShouldReturnNull() {
        assertNull(TerminalSessionStatus.fromValue("unknown"));
    }
}
