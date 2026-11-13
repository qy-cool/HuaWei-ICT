package cloide.server.module.auth.oidc;

import static org.junit.jupiter.api.Assertions.*;
import cloide.server.common.BusinessException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class OidcStateTests {
    private JdbcTemplate jdbc;
    private OidcState state;
    @BeforeEach void setup(){
        JdbcDataSource db=new JdbcDataSource();
        db.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V45__enterprise_oidc.sql")).execute(db);
        jdbc=new JdbcTemplate(db);state=new OidcState(jdbc,Clock.systemUTC());
    }
    @AfterEach void close(){if(jdbc!=null)jdbc.execute("SHUTDOWN");}
    @Test void aDifferentBrowserCannotConsumeTheOriginalOneTimeAttempt(){
        var original=state.begin();var other=state.begin();
        assertThrows(BusinessException.class,()->state.consume(original.state(),other.verifier()));
        assertEquals(OidcState.hash(original.nonce()),state.consume(original.state(),original.verifier()));
        assertThrows(BusinessException.class,()->state.consume(original.state(),original.verifier()));
    }
    @Test void expiredAttemptsAreRejectedAndRemoved(){
        var attempt=state.begin();
        jdbc.update("UPDATE oidc_login_attempts SET expires_at=?",Timestamp.from(Instant.EPOCH.plusSeconds(1)));
        assertThrows(BusinessException.class,()->state.consume(attempt.state(),attempt.verifier()));
        state.expire();
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM oidc_login_attempts",Integer.class));
    }
}
