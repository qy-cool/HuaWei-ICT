package cloide.server.module.auth.oidc;

import cloide.server.common.BusinessException;
import cloide.server.common.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class OidcState {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    record Attempt(String state,String verifier,String nonce) {
        @Override public String toString(){return "OIDC login attempt";}
    }
    static byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch(NoSuchAlgorithmException error) {throw new IllegalStateException(error);}
    }
    static String hash(String value){return HexFormat.of().formatHex(digest(value));}
    static String base64(byte[] bytes){return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    private String randomValue(){byte[] b=new byte[32];random.nextBytes(b);return base64(b);}
    Attempt begin(){
        Attempt a=new Attempt(randomValue(),randomValue(),randomValue());
        jdbc.update("INSERT INTO oidc_login_attempts(state_hash,verifier_hash,nonce_hash,expires_at) VALUES(?,?,?,?)",
                hash(a.state()),hash(a.verifier()),hash(a.nonce()),Timestamp.from(clock.instant().plusSeconds(300)));
        return a;
    }
    // Commit consumption before calling the external IdP. A failed exchange still consumes state.
    @Transactional
    public String consume(String state,String verifier){
        if(state==null || verifier==null || !state.matches("[A-Za-z0-9_-]{43}") || !verifier.matches("[A-Za-z0-9_-]{43}"))throw denied();
        String hashed=hash(state);
        String nonce=jdbc.query("""
            SELECT nonce_hash FROM oidc_login_attempts WHERE state_hash=? AND verifier_hash=? AND expires_at>? FOR UPDATE
            """,(rs,i)->rs.getString(1),hashed,hash(verifier),Timestamp.from(clock.instant()))
                .stream().findFirst().orElseThrow(OidcState::denied);
        jdbc.update("DELETE FROM oidc_login_attempts WHERE state_hash=?",hashed);
        return nonce;
    }
    @Scheduled(fixedDelay=300000)
    public void expire(){jdbc.update("DELETE FROM oidc_login_attempts WHERE expires_at<?",Timestamp.from(clock.instant()));}
    static BusinessException denied(){return new BusinessException(ErrorCode.UNAUTHORIZED);}
}
