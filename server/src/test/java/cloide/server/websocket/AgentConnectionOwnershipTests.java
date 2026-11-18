package cloide.server.websocket;

import static org.junit.jupiter.api.Assertions.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.util.UUID;

class AgentConnectionOwnershipTests {
    @Test void displacedInstanceCannotPublishOrReleaseNewConnection() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        var db = new JdbcTemplate(source);
        db.execute("CREATE TABLE servers(id BIGINT PRIMARY KEY, deleted INT, agent_token_revoked_at DATETIME)");
        db.update("INSERT INTO servers(id,deleted) VALUES (8,0)");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V47__node_connection_ownership.sql")).execute(source);
        var first = new AgentConnectionOwnership(db);
        var second = new AgentConnectionOwnership(db);
        first.claim(8,"old",()->{});
        assertTrue(first.owns(8,"old"));
        second.claim(8,"new",()->{});
        assertFalse(first.withOwnership(8,"old",()->fail("过期持有者不应继续写入")));
        first.release(8,"old");
        assertTrue(second.owns(8,"new"));
        db.update("UPDATE servers SET agent_token_revoked_at=CURRENT_TIMESTAMP WHERE id=8");
        assertFalse(second.owns(8,"new"));
        assertFalse(second.withOwnership(8,"new",()->fail("已撤销的节点不能更新状态")));
    }
}
