package cloide.server.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import cloide.server.common.BusinessException;
import cloide.server.common.ErrorCode;
import cloide.server.module.terminal.enums.TerminalSessionStatus;
import cloide.server.module.terminal.service.TerminalSessionService;
import java.io.IOException;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;

/** 将已认证 Agent 的终端响应仅回送到创建会话的 Monitor 连接。 */
@Component
public class TerminalAgentRelayService {
    private final TerminalSessionService terminalSessionService;
    private final TerminalRelayRegistry relayRegistry;
    private final TerminalOutputRateLimiter outputRateLimiter;
    private final TerminalRelayLifecycleService lifecycleService;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final MonitorSessionTerminationService terminationService;

    /** 将完整的生产依赖注入终端 Agent 中继，测试使用下方的隔离构造器。 */
    @Autowired
    public TerminalAgentRelayService(TerminalSessionService terminalSessionService, TerminalRelayRegistry relayRegistry,
            TerminalOutputRateLimiter outputRateLimiter, TerminalRelayLifecycleService lifecycleService,
            ObjectMapper objectMapper, Clock clock, MonitorSessionTerminationService terminationService) {
        this.terminalSessionService = terminalSessionService;
        this.relayRegistry = relayRegistry;
        this.outputRateLimiter = outputRateLimiter;
        this.lifecycleService = lifecycleService;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.terminationService = terminationService;
    }

    /** 保持既有隔离测试可只提供终端中继所需依赖。 */
    TerminalAgentRelayService(TerminalSessionService terminalSessionService, TerminalRelayRegistry relayRegistry,
            TerminalOutputRateLimiter outputRateLimiter, TerminalRelayLifecycleService lifecycleService,
            ObjectMapper objectMapper, Clock clock) {
        this(terminalSessionService, relayRegistry, outputRateLimiter, lifecycleService, objectMapper, clock, null);
    }

    /** 验证 Agent 归属并将合法终端响应路由给原浏览器。 */
    public void relay(AgentWebSocketSession agentSession, TerminalMessage message) throws IOException {
        TerminalProtocolValidator.validateAgentMessage(message);
        Long serverId = message.payload().get("server_id").longValue();
        if (!serverId.equals(agentSession.serverId())) {
            throw new BusinessException(ErrorCode.TERMINAL_ACCESS_DENIED);
        }
        String sessionId = message.payload().path("session_id").asText(null);
        if (sessionId == null && TerminalMessageType.TERMINAL_ERROR.value().equals(message.type())) {
            return;
        }
        TerminalRelayRegistry.TerminalRelayBinding binding = sessionId == null ? null : relayRegistry.get(sessionId);
        if (binding == null || !binding.serverId().equals(serverId)) {
            throw new BusinessException(ErrorCode.TERMINAL_SESSION_NOT_FOUND);
        }
        if (TerminalMessageType.TERMINAL_OUTPUT.value().equals(message.type())
                && !outputRateLimiter.allow(sessionId, message.payload().path("data").textValue())) {
            lifecycleService.closeBindingFromServer(binding, TerminalSessionStatus.CLOSED.value(), "output_rate_exceeded");
            return;
        }
        if (TerminalMessageType.TERMINAL_OPENED.value().equals(message.type())) {
            terminalSessionService.markOpened(sessionId, message.payload().path("shell").asText());
        } else if (TerminalMessageType.TERMINAL_CLOSED.value().equals(message.type())) {
            lifecycleService.closeBinding(binding, TerminalSessionStatus.CLOSED.value(),
                    message.payload().path("reason").asText());
        } else {
            terminalSessionService.touchSession(sessionId);
        }
        String body = objectMapper.createObjectNode().put("type", message.type())
                .put("message_id", message.messageId()).put("timestamp", OffsetDateTime.now(clock).toString())
                .set("payload", message.payload()).toString();
        try {
            if (!binding.monitorSession().send(new TextMessage(body))) {
                terminateNormally(binding.monitorSession());
            }
        } catch (MonitorBackpressureException exception) {
            terminateForBackpressure(binding.monitorSession());
        } catch (IOException exception) {
            terminateNormally(binding.monitorSession());
        }
    }

    /** 统一处理已关闭或普通 I/O 失败导致的浏览器会话收口。 */
    private void terminateNormally(MonitorWebSocketSession monitorSession) {
        if (terminationService != null) {
            terminationService.terminateNormally(monitorSession);
        } else {
            lifecycleService.closeMonitorSessions(monitorSession);
        }
    }

    /** 统一处理 Spring 检测到的浏览器慢消费者。 */
    private void terminateForBackpressure(MonitorWebSocketSession monitorSession) {
        if (terminationService != null) {
            terminationService.terminateForBackpressure(monitorSession);
        } else {
            lifecycleService.closeMonitorSessions(monitorSession, "monitor_backpressure");
        }
    }
}
