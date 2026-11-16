package cloide.server.websocket;

import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 同一节点的最新认证连接拥有工程通道。每个 JVM 启动时分配独立身份。 */
@Component
@ConditionalOnProperty(name="cloide.engineering.cluster-connections", havingValue="true")
public class AgentConnectionOwnership {
    private final String instance = UUID.randomUUID().toString();
    private final JdbcTemplate db;

    public AgentConnectionOwnership(JdbcTemplate db) { this.db = db; }

    @Transactional
    public void claim(long node, String session, Runnable reauthenticate) {
        db.queryForObject("SELECT id FROM servers WHERE id=? AND deleted=0 FOR UPDATE",Long.class,node);
        reauthenticate.run();
        db.update("""
                INSERT INTO node_connection_owners(server_id,instance_id,session_id) VALUES (?,?,?)
                ON DUPLICATE KEY UPDATE instance_id=?,session_id=?,claimed_at=CURRENT_TIMESTAMP(6)
                """,node,instance,session,instance,session);
    }

    public boolean owns(long node, String session) {
        return !db.queryForList("""
                SELECT c.server_id FROM node_connection_owners c JOIN servers s ON s.id=c.server_id
                WHERE c.server_id=? AND c.instance_id=? AND c.session_id=? AND s.deleted=0 AND s.agent_token_revoked_at IS NULL
                """,Long.class,node,instance,session).isEmpty();
    }

    @Transactional
    public boolean withOwnership(long node, String session, Runnable operation) {
        // 与 claim 和凭据撤销保持相同的锁顺序，节点心跳也会更新 servers。
        if (db.queryForList("""
                SELECT id FROM servers WHERE id=? AND deleted=0 AND agent_token_revoked_at IS NULL FOR UPDATE
                """,Long.class,node).isEmpty()) return false;
        if (db.queryForList("""
                SELECT server_id FROM node_connection_owners WHERE server_id=? AND instance_id=? AND session_id=? FOR UPDATE
                """,Long.class,node,instance,session).isEmpty()) return false;
        operation.run();
        return true;
    }

    public void release(long node, String session) {
        db.update("DELETE FROM node_connection_owners WHERE server_id=? AND instance_id=? AND session_id=?",node,instance,session);
    }
    public void revoke(long node) {
        db.update("DELETE FROM node_connection_owners WHERE server_id=?",node);
    }
}
