package cloide.server.module.job;

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
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;

@Component
@RequiredArgsConstructor
public class AgentJobChannel {
    @org.springframework.beans.factory.annotation.Value("${cloide.engineering.recovery:false}")
    private boolean recovering;
    @org.springframework.beans.factory.annotation.Autowired
    private cloide.server.module.project.service.NodeCapabilities capabilities;
    private final ProjectRunService runs;
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
        if ("job.ready".equals(message.type())) {
            if (integer(payload, "protocol_version") != 1) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
            }
            if (payload.has("capabilities") && session.needsCapabilitiesRefresh(clock.instant())) {
                capabilities.record(session.serverId(),payload.get("capabilities"));
                session.capabilitiesUpdated(clock.instant());
            }
            session.enableJobProtocol();
            dispatch(session);
            return;
        }
        if (!session.supportsJobProtocol()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        }
        String jobId = text(payload, "job_id");
        if (!canonicalUuid(jobId)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        }
        long serverId = session.serverId();
        switch (message.type()) {
            case "job.accepted" -> runs.accepted(serverId, jobId);
            case "job.log" -> runs.log(serverId, jobId, integer(payload, "seq"), text(payload, "text"));
            case "job.result" -> {
                runs.finish(serverId, jobId, text(payload, "status"), integer(payload, "exit_code"), text(payload, "output"));
                // finish 的事务已经提交，回执即使失联，节点仍能重放同一个结果。
                send(session, "job.result.ack", Map.of("job_id", jobId));
            }
            default -> throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        }
    }

    public void wake(long serverId) throws IOException {
        var session = connections.current(serverId);
        if (session.isPresent() && session.get().supportsJobProtocol()) {
            dispatch(session.get());
        }
    }

    private void dispatch(AgentWebSocketSession session) throws IOException {
        if (recovering) return;
        var next = runs.next(session.serverId());
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
