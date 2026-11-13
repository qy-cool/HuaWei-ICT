package cloide.server.module.server.service;

import cloide.server.common.BusinessException;
import cloide.server.common.ErrorCode;
import cloide.server.module.server.entity.ServerEntity;
import cloide.server.module.server.mapper.ServerMapper;
import cloide.server.module.server.vo.AgentTokenVo;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 管理员预登记节点，节点持短时邀请交换既有 WSS 凭据。 */
@Service
@RequiredArgsConstructor
public class NodeEnrollmentService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcTemplate jdbc;
    private final ServerMapper servers;
    private final AgentTokenService agentTokens;

    @Transactional
    public String invite(String name) {
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        jdbc.update("DELETE FROM node_enrollment_tickets WHERE expires_at <= UTC_TIMESTAMP(6)");
        jdbc.update("""
                INSERT INTO node_enrollment_tickets(token_hash, node_name, expires_at)
                VALUES (?, ?, DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 10 MINUTE))
                """, digest(ticket), name.strip());
        return ticket;
    }

    @Transactional
    public AgentTokenVo redeem(String ticket) {
        byte[] hash = digest(ticket);
        var names = jdbc.queryForList("""
                SELECT node_name FROM node_enrollment_tickets
                WHERE token_hash = ? AND expires_at > UTC_TIMESTAMP(6) FOR UPDATE
                """, String.class, hash);
        if (names.size() != 1 || jdbc.update("""
                DELETE FROM node_enrollment_tickets
                WHERE token_hash = ? AND expires_at > UTC_TIMESTAMP(6)
                """, hash) != 1) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        var server = new ServerEntity();
        server.setName(names.getFirst());
        server.setHost("node-" + UUID.randomUUID());
        server.setSshAuthType("none");
        server.setSshHost("");
        server.setSshPort(0);
        server.setSshUser("");
        if (servers.insertServerBase(server) != 1 || server.getId() == null) {
            throw new BusinessException(ErrorCode.DATABASE_ERROR);
        }
        return agentTokens.register(server.getId());
    }

    private static byte[] digest(String ticket) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(ticket.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
