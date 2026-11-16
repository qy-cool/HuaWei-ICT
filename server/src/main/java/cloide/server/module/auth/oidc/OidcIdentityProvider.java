package cloide.server.module.auth.oidc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwsHeader;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Jwks;
import io.jsonwebtoken.security.RsaPublicJwk;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class OidcIdentityProvider {
    private final OidcSettings settings;
    private final Clock clock;
    private final ObjectMapper json;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    private static String encode(String s){return URLEncoder.encode(s,StandardCharsets.UTF_8);}
    private static String form(Map<String,String> fields){
        return fields.entrySet().stream().map(e->encode(e.getKey())+"="+encode(e.getValue())).collect(Collectors.joining("&"));
    }
    String authorizationUrl(OidcState.Attempt a) {
        return settings.authorization+"?"+form(Map.of("client_id",settings.clientId,"redirect_uri",settings.redirect,
                "response_type","code","scope","openid","state",a.state(),"nonce",a.nonce(),"code_challenge_method","S256",
                "code_challenge",OidcState.base64(OidcState.digest(a.verifier()))));
    }
    String exchange(String code,String verifier,String nonceHash) {
        String stage="exchange";
        try {
            String basic=Base64.getEncoder().encodeToString((encode(settings.clientId)+":"+encode(settings.clientSecret))
                    .getBytes(StandardCharsets.US_ASCII));
            String body=form(Map.of("grant_type","authorization_code","code",code,"code_verifier",verifier,"redirect_uri",settings.redirect));
            JsonNode tokens=json.readTree(send(HttpRequest.newBuilder(URI.create(settings.token)).header("Authorization","Basic "+basic)
                    .header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body))));
            String jwt=tokens.path("id_token").asText("");
            if(jwt.length()>32768 || jwt.isBlank())throw OidcState.denied();
            stage="keyset";
            var keys=Jwks.setParser().build().parse(send(HttpRequest.newBuilder(URI.create(settings.jwks)).GET())).getKeys();
            stage="signature";
            // The locator only returns an RSA verification key for the explicitly permitted JWS algorithm.
            Claims claims=Jwts.parser()
                    .keyLocator(header->{
                        if(!(header instanceof JwsHeader h) || !"RS256".equals(h.getAlgorithm()) || h.getKeyId()==null ||
                                h.getCompressionAlgorithm()!=null) throw OidcState.denied();
                        var matching=keys.stream().filter(k->k instanceof RsaPublicJwk r && h.getKeyId().equals(r.getId())
                                && (r.getAlgorithm()==null || "RS256".equals(r.getAlgorithm()))
                                && (r.getPublicKeyUse()==null || "sig".equals(r.getPublicKeyUse()))
                                && (r.getOperations()==null || r.getOperations().isEmpty() ||
                                r.getOperations().stream().anyMatch(op->"verify".equals(op.getId())))).toList();
                        if(matching.size()!=1)throw OidcState.denied();
                        return matching.get(0).toKey();
                    }).requireIssuer(settings.issuer).requireAudience(settings.clientId)
                    .clock(()->Date.from(clock.instant())).build().parseSignedClaims(jwt).getPayload();
            String nonce=claims.get("nonce",String.class);
            stage="claims";
            String subject=claims.getSubject();
            String azp=claims.get("azp",String.class);
            if (nonce==null || !OidcState.hash(nonce).equals(nonceHash) || subject==null || subject.isBlank() ||
                    subject.getBytes(StandardCharsets.UTF_8).length>255 || claims.getExpiration()==null || claims.getIssuedAt()==null ||
                    claims.getIssuedAt().toInstant().isAfter(clock.instant().plusSeconds(60)) ||
                    claims.getIssuedAt().toInstant().isBefore(clock.instant().minusSeconds(360)) ||
                    ((azp!=null || claims.getAudience().size()>1) && !settings.clientId.equals(azp))) throw OidcState.denied();
            return subject;
        } catch(InterruptedException err){Thread.currentThread().interrupt();throw OidcState.denied();}
        catch(IOException | RuntimeException invalid){
            log.debug("OIDC verification failed stage={} error={}",stage,invalid.getClass().getSimpleName());
            throw OidcState.denied();
        }
    }
    private String send(HttpRequest.Builder request) throws IOException,InterruptedException {
        var response=http.send(request.timeout(Duration.ofSeconds(8)).header("Accept","application/json").build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if(response.statusCode()!=200 || response.body().length>262144)throw OidcState.denied();
        return new String(response.body(),StandardCharsets.UTF_8);
    }
}
