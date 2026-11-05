package cloide.server.websocket;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 表示终端通道使用的统一 WebSocket 消息外层结构。
 *
 * <p>该模型仅冻结 Java、Go Agent 和浏览器共享的 JSON 契约，不承担会话路由或终端执行职责。</p>
 *
 * @param type 终端消息类型
 * @param messageId 请求或响应关联 ID
 * @param timestamp UTC ISO-8601 时间
 * @param payload 终端业务载荷
 */
public record TerminalMessage(
        String type,
        @JsonProperty("message_id")
        String messageId,
        String timestamp,
        JsonNode payload) {
}
