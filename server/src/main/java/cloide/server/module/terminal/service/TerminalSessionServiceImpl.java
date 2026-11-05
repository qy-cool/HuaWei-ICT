package cloide.server.module.terminal.service;

import cloide.server.common.BusinessException;
import cloide.server.common.ErrorCode;
import cloide.server.config.AppProperties;
import cloide.server.module.auth.service.UserService;
import cloide.server.module.server.service.ServerService;
import cloide.server.module.terminal.entity.TerminalSessionEntity;
import cloide.server.module.terminal.enums.TerminalSessionStatus;
import cloide.server.module.terminal.mapper.TerminalSessionMapper;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 实现终端元数据的创建、状态转换和单 JVM 会话额度控制。 */
@Service
public class TerminalSessionServiceImpl implements TerminalSessionService {
    private final TerminalSessionMapper sessionMapper;
    // users/servers 表数据所有权分别在 auth/server 模块，校验统一走 Service 契约。
    private final UserService userService;
    private final ServerService serverService;
    private final AppProperties properties;
    private final Clock clock;
    private final ReentrantLock globalQuotaLock = new ReentrantLock();
    private final ConcurrentHashMap<String, ReentrantLock> quotaLocks = new ConcurrentHashMap<>();

    public TerminalSessionServiceImpl(TerminalSessionMapper sessionMapper, UserService userService,
            ServerService serverService, AppProperties properties, Clock clock) {
        this.sessionMapper = sessionMapper;
        this.userService = userService;
        this.serverService = serverService;
        this.properties = properties;
        this.clock = clock;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public TerminalSessionEntity openSession(Long userId, Long serverId, String openMessageId) {
        ReentrantLock quotaLock = quotaLocks.computeIfAbsent(
                userId + ":" + serverId, key -> new ReentrantLock());
        globalQuotaLock.lock();
        quotaLock.lock();
        try {
            registerQuotaUnlock(globalQuotaLock, quotaLock);
            return openSessionUnderQuotaLock(userId, serverId, openMessageId);
        } finally {
            if (!TransactionSynchronizationManager.isSynchronizationActive()) {
                quotaLock.unlock();
                globalQuotaLock.unlock();
            }
        }
    }

    private TerminalSessionEntity openSessionUnderQuotaLock(Long userId, Long serverId, String openMessageId) {
        TerminalSessionEntity existing = sessionMapper.selectByUserIdAndOpenMessageId(userId, openMessageId);
        if (existing != null) {
            return existing;
        }
        if (!userService.isApprovedUser(userId)) {
            throw new BusinessException(ErrorCode.TERMINAL_ACCESS_DENIED);
        }
        if (!serverService.existsActive(serverId)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        if (!serverService.isAgentOnline(serverId)) {
            throw new BusinessException(ErrorCode.TERMINAL_AGENT_OFFLINE);
        }
        enforceLimits(userId, serverId);
        LocalDateTime now = LocalDateTime.now(clock);
        TerminalSessionEntity session = new TerminalSessionEntity();
        session.setSessionId(UUID.randomUUID().toString());
        session.setOpenMessageId(openMessageId);
        session.setUserId(userId);
        session.setServerId(serverId);
        session.setStatus(TerminalSessionStatus.OPENING.value());
        session.setLastActivityAt(now);
        sessionMapper.insertSession(session);
        return session;
    }

    private void registerQuotaUnlock(ReentrantLock globalLock, ReentrantLock quotaLock) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                quotaLock.unlock();
                globalLock.unlock();
            }
        });
    }

    /** {@inheritDoc} */
    @Override
    public void markOpened(String sessionId, String shellIdentifier) {
        if (sessionMapper.markOpened(sessionId, shellIdentifier, LocalDateTime.now(clock)) == 0) {
            throw new BusinessException(ErrorCode.TERMINAL_SESSION_STATE_CONFLICT);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void touchSession(String sessionId) {
        if (sessionMapper.updateLastActivity(sessionId, LocalDateTime.now(clock)) == 0) {
            throw new BusinessException(ErrorCode.TERMINAL_SESSION_STATE_CONFLICT);
        }
    }

    /** {@inheritDoc} */
    @Override
    public TerminalSessionEntity requireActiveSession(Long userId, String sessionId) {
        if (!userService.isApprovedUser(userId)) {
            throw new BusinessException(ErrorCode.TERMINAL_ACCESS_DENIED);
        }
        TerminalSessionEntity session = sessionMapper.selectBySessionId(sessionId);
        if (session == null) {
            throw new BusinessException(ErrorCode.TERMINAL_SESSION_NOT_FOUND);
        }
        if (!userId.equals(session.getUserId())) {
            throw new BusinessException(ErrorCode.TERMINAL_ACCESS_DENIED);
        }
        if (!TerminalSessionStatus.OPENING.value().equals(session.getStatus())
                && !TerminalSessionStatus.OPEN.value().equals(session.getStatus())) {
            throw new BusinessException(ErrorCode.TERMINAL_SESSION_STATE_CONFLICT);
        }
        return session;
    }

    /** {@inheritDoc} */
    @Override
    public void closeSession(String sessionId, String status, String reason) {
        sessionMapper.closeSession(sessionId, status, reason, LocalDateTime.now(clock));
    }

    /** {@inheritDoc} */
    @Override
    public void closeExpiredSessions() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime idleDeadline = now.minus(properties.getTerminal().getIdleTimeoutMinutes(), ChronoUnit.MINUTES);
        LocalDateTime maximumDeadline = now.minus(properties.getTerminal().getMaxSessionHours(), ChronoUnit.HOURS);
        for (TerminalSessionEntity session : sessionMapper.selectAllActive()) {
            if (session.getLastActivityAt().isBefore(idleDeadline)) {
                closeSession(session.getSessionId(), TerminalSessionStatus.TIMEOUT.value(), "idle_timeout");
            } else if (session.getCreatedAt().isBefore(maximumDeadline)) {
                closeSession(session.getSessionId(), TerminalSessionStatus.TIMEOUT.value(), "max_session_duration");
            }
        }
    }

    private void enforceLimits(Long userId, Long serverId) {
        int userCount = sessionMapper.selectActiveByUserId(userId).size();
        int serverCount = sessionMapper.selectActiveByServerId(serverId).size();
        if (userCount >= properties.getTerminal().getMaxSessionsPerUser()
                || serverCount >= properties.getTerminal().getMaxSessionsPerServer()
                || sessionMapper.countActiveUsers() >= properties.getTerminal().getMaxOperatingUsers()
                || sessionMapper.selectAllActive().size() >= properties.getTerminal().getMaxSessions()) {
            throw new BusinessException(ErrorCode.TERMINAL_SESSION_LIMIT_REACHED);
        }
    }
}
