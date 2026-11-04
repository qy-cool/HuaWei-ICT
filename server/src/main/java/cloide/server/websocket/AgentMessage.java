package cloide.server.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Agent WebSocket 统一消息结构。
 *
 * @param type 消息类型
 * @param messageId 消息 ID
 * @param timestamp ISO-8601 时间字符串
 * @param payload 业务载荷
 */
public record AgentMessage(
        String type,
        @JsonProperty("message_id")
        String messageId,
        String timestamp,
        JsonNode payload) {
}
