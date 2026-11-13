package cloide.server.module.repository;

import cloide.server.common.BusinessException;
import cloide.server.common.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 长期私钥仅供中控签 JWT，各操作只领取所需权限，HTTP 禁止重定向。 */
@Component
public class GitHubApp {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper json;
    private final Clock clock;
    private final URI origin;
    private final String appId, keyFile, webhookSecret, owner, template;
    private final long installation;

    public GitHubApp(ObjectMapper json, Clock clock, @Value("${cloide.github.api:https://api.github.com}") URI origin,
            @Value("${cloide.github.app-id:}") String appId, @Value("${cloide.github.private-key-file:}") String keyFile,
            @Value("${cloide.github.installation-id:0}") long installation, @Value("${cloide.github.owner:}") String owner,
            @Value("${cloide.github.template:}") String template, @Value("${cloide.github.webhook-secret:}") String webhookSecret) {
        boolean local = "http".equals(origin.getScheme()) && "127.0.0.1".equals(origin.getHost());
        if ((!local && !"https".equals(origin.getScheme())) || origin.getHost() == null
                || origin.getUserInfo()!=null || origin.getQuery()!=null || origin.getFragment()!=null) {
            throw new IllegalArgumentException("GitHub API must be a trusted HTTPS origin or loopback test service");
        }
        this.json=json; this.clock=clock; this.origin=origin; this.appId=appId; this.keyFile=keyFile;
        this.installation=installation; this.owner=owner; this.template=template; this.webhookSecret=webhookSecret;
    }

    long installation() { return installation; }
    String owner() { return checkedName(owner); }
    String template() {
        var parts=template.split("/",-1);
        if (parts.length!=2) throw new BusinessException(ErrorCode.BAD_REQUEST);
        return checkedName(parts[0])+"/"+checkedName(parts[1]);
    }
    static String checkedName(String name) {
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        return name;
    }
    String token(Map<String,String> permissions, Long repoId, String repoName) {
        if (installation<=0 || appId.isBlank() || keyFile.isBlank()) throw new BusinessException(ErrorCode.GITHUB_APP_NOT_CONFIGURED);
        Map<String,Object> body = new HashMap<>();
        body.put("permissions",permissions);
        if (repoId!=null) body.put("repository_ids",List.of(repoId));
        else if (repoName!=null) body.put("repositories",List.of(repoName));
        var result = call("POST","/app/installations/"+installation+"/access_tokens",jwt(),body,201);
        String token = result.path("token").asText("");
        if (token.isBlank()) throw new BusinessException(ErrorCode.RESOURCE_CONFLICT);
        return token;
    }
    private String jwt() {
        try {
            String pem = Files.readString(Path.of(keyFile)).strip();
            if (!pem.startsWith("-----BEGIN PRIVATE KEY-----")) throw new GeneralSecurityException("expected PKCS8");
            byte[] der = Base64.getMimeDecoder().decode(pem.replace("-----BEGIN PRIVATE KEY-----","").replace("-----END PRIVATE KEY-----",""));
            var key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
            Instant now = clock.instant();
            return Jwts.builder().issuer(appId).issuedAt(Date.from(now.minusSeconds(60)))
                    .expiration(Date.from(now.plusSeconds(480))).signWith(key,Jwts.SIG.RS256).compact();
        } catch (IOException | GeneralSecurityException | IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.GITHUB_APP_NOT_CONFIGURED);
        }
    }
    JsonNode call(String method,String path,String token,Object body,int... accepted) {
        try {
            var request=HttpRequest.newBuilder(URI.create(origin.toString().replaceAll("/$","")+path))
                    .timeout(Duration.ofSeconds(15)).header("Accept","application/vnd.github+json")
                    .header("X-GitHub-Api-Version","2022-11-28").header("Authorization","Bearer "+token)
                    .header("User-Agent","Cloide-GitHub-App").header("Content-Type","application/json")
                    .method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))).build();
            var result=http.send(request,HttpResponse.BodyHandlers.ofInputStream());
            try (var input=result.body()) {
                if (Arrays.stream(accepted).noneMatch(code->code==result.statusCode())) {
                    throw new BusinessException(result.statusCode() >= 500 || result.statusCode() == 429
                            ? ErrorCode.GITHUB_API_UNAVAILABLE : ErrorCode.RESOURCE_CONFLICT);
                }
                if (result.statusCode()==404) return json.nullNode();
                byte[] bytes = input.readNBytes(2*1024*1024+1);
                if (bytes.length>2*1024*1024) throw new BusinessException(ErrorCode.RESOURCE_CONFLICT);
                return json.readTree(bytes);
            }
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.GITHUB_API_UNAVAILABLE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.RESOURCE_CONFLICT);
        }
    }

    void verify(byte[] bytes,String signature) {
        if (webhookSecret.isBlank() || signature==null || !signature.matches("sha256=[a-f0-9]{64}")) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        try {
            Mac mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            if (!MessageDigest.isEqual(HexFormat.of().parseHex(signature.substring(7)),mac.doFinal(bytes))) {
                throw new BusinessException(ErrorCode.UNAUTHORIZED);
            }
        } catch (GeneralSecurityException impossible) { throw new IllegalStateException(impossible); }
    }
    static String sha(byte[] bytes) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch (NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
    }
}
