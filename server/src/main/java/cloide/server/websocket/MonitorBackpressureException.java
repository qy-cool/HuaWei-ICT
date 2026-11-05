package cloide.server.websocket;

import java.io.IOException;
import org.springframework.web.socket.handler.SessionLimitExceededException;

/** 标识 Spring 出站会话缓冲或发送时限已耗尽的 Monitor 慢消费者。 */
public class MonitorBackpressureException extends IOException {

    /** 将 Spring 的会话限额异常转换为项目可统一处理的背压异常。 */
    public MonitorBackpressureException(SessionLimitExceededException cause) {
        super("Monitor WebSocket outbound limit exceeded", cause);
    }
}
