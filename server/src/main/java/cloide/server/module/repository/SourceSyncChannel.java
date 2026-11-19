package cloide.server.module.repository;

import cloide.server.common.BusinessException;
import cloide.server.common.ErrorCode;
import cloide.server.websocket.AgentConnectionRegistry;
import cloide.server.websocket.AgentMessage;
import cloide.server.websocket.AgentWebSocketSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;

/**
 * source.sync.* 帧族的 WSS 通道：与 job.execute 独立协商，只有发送过
 * source.sync.ready 的会话才会收到同步派发；终态先落库再确认，节点可安全重放。
 */
@Component
@RequiredArgsConstructor
public class SourceSyncChannel {
    @org.springframework.beans.factory.annotation.Value("${cloide.engineering.recovery:false}")
    private boolean recovering;
    private final SourceSyncService syncs;
    private final AgentConnectionRegistry connections;
    private final ObjectMapper json;
    private final Clock clock;

    public void handle(AgentWebSocketSession session, AgentMessage message) throws IOException {
        if (!connections.isCurrent(session)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        JsonNode payload = message.payload();
        if (payload == null || !payload.isObject()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        }
        if ("source.sync.ready".equals(message.type())) {
            if (integer(payload, "protocol_version") != 1) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
            }
            session.enableSourceSync();
            dispatch(session);
            return;
        }
        if (!session.supportsSourceSync()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        }
        String syncId = text(payload, "sync_id");
        if (!canonicalUuid(syncId)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        }
        long serverId = session.serverId();
        switch (message.type()) {
            case "source.sync.accepted" -> syncs.accepted(serverId, syncId);
            case "source.sync.progress" -> syncs.progress(serverId, syncId, text(payload, "stage"));
            case "source.sync.result" -> {
                syncs.finish(serverId, syncId, text(payload, "status"),
                        optionalText(payload, "verified_commit_sha"), optionalText(payload, "verified_tree_sha"),
                        optionalText(payload, "verified_bundle_sha256"), optionalText(payload, "output"));
                // finish 的事务已提交，回执即使失联，节点仍能重放同一个结果。
                send(session, "source.sync.result.ack", Map.of("sync_id", syncId));
            }
            default -> throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        }
    }

    public void wake(long serverId) throws IOException {
        var session = connections.current(serverId);
        if (session.isPresent() && session.get().supportsSourceSync()) {
            dispatch(session.get());
        }
    }

    private void dispatch(AgentWebSocketSession session) throws IOException {
        if (recovering) return;
        var next = syncs.next(session.serverId());
        if (next.isPresent() && connections.isCurrent(session)) {
            send(session, next.get().type(), next.get().payload());
        }
    }

    private void send(AgentWebSocketSession session, String type, Map<String, Object> payload) throws IOException {
        var frame = new AgentMessage(type, UUID.randomUUID().toString(), Instant.now(clock).toString(), json.valueToTree(payload));
        session.send(new TextMessage(json.writeValueAsString(frame)));
    }

    private static String text(JsonNode payload, String field) {
        JsonNode value = payload.path(field);
        if (!value.isTextual()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        }
        return value.textValue();
    }

    /** 终态回执的核验摘要与说明允许缺席（如 failed/cancelled），缺席按 null 交服务端裁决。 */
    private static String optionalText(JsonNode payload, String field) {
        JsonNode value = payload.path(field);
        return value.isTextual() ? value.textValue() : null;
    }

    private static int integer(JsonNode payload, String field) {
        JsonNode value = payload.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        }
        return value.intValue();
    }

    private static boolean canonicalUuid(String id) {
        try {
            return UUID.fromString(id).toString().equals(id);
        } catch (IllegalArgumentException invalidId) {
            return false;
        }
    }
}
