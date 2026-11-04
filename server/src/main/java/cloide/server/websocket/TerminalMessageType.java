package cloide.server.websocket;

/**
 * 定义终端会话在 Monitor、Java 后端和 Agent 之间传输的消息类型。
 */
public enum TerminalMessageType {
    TERMINAL_OPEN("terminal.open"),
    TERMINAL_OPENED("terminal.opened"),
    TERMINAL_INPUT("terminal.input"),
    TERMINAL_OUTPUT("terminal.output"),
    TERMINAL_RESIZE("terminal.resize"),
    TERMINAL_CLOSE("terminal.close"),
    TERMINAL_CLOSED("terminal.closed"),
    TERMINAL_ERROR("terminal.error");

    private final String value;

    /**
     * 创建终端消息类型枚举项。
     *
     * @param value 协议中的稳定字符串值
     */
    TerminalMessageType(String value) {
        this.value = value;
    }

    /**
     * 返回协议中使用的稳定消息类型字符串。
     *
     * @return 消息类型字符串
     */
    public String value() {
        return value;
    }
}
