package cloide.server.module.terminal.enums;

/**
 * 定义终端会话元数据的持久化状态，用于后续会话生命周期的受控转换。
 */
public enum TerminalSessionStatus {
    /** Java 已接受创建请求，等待 Agent 创建 PTY。 */
    OPENING("opening"),
    /** Agent 已确认 PTY 创建完成。 */
    OPEN("open"),
    /** 用户、Agent 或系统已正常关闭会话。 */
    CLOSED("closed"),
    /** 会话因空闲或最长生命周期限制而关闭。 */
    TIMEOUT("timeout"),
    /** 会话因 Agent 创建或中继失败而关闭。 */
    ERROR("error");

    private final String value;

    /**
     * 创建终端会话状态枚举项。
     *
     * @param value 数据库中持久化的稳定值
     */
    TerminalSessionStatus(String value) {
        this.value = value;
    }

    /**
     * 返回数据库中持久化的稳定状态值。
     *
     * @return 状态值
     */
    public String value() {
        return value;
    }

    /**
     * 将数据库状态值转换为枚举；未知值返回 null，供调用方显式拒绝脏数据。
     *
     * @param value 数据库状态值
     * @return 对应枚举，未知时为 null
     */
    public static TerminalSessionStatus fromValue(String value) {
        if (value == null) {
            return null;
        }
        for (TerminalSessionStatus status : values()) {
            if (status.value.equals(value)) {
                return status;
            }
        }
        return null;
    }
}
