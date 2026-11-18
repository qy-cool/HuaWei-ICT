package cloide.server.module.auth.oidc;

import java.net.URI;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class OidcSettings {
    final boolean enabled;
    final String issuer,authorization,token,jwks,clientId,clientSecret,redirect;

    public OidcSettings(@Value("${cloide.oidc.enabled:false}") boolean enabled,
            @Value("${cloide.oidc.issuer:}") String issuer,
            @Value("${cloide.oidc.authorization-endpoint:}") String authorization,
            @Value("${cloide.oidc.token-endpoint:}") String token,
            @Value("${cloide.oidc.jwks-uri:}") String jwks,
            @Value("${cloide.oidc.client-id:}") String clientId,
            @Value("${cloide.oidc.client-secret:}") String clientSecret,
            @Value("${cloide.oidc.redirect-uri:}") String redirect) {
        this.enabled=enabled;this.issuer=issuer;this.authorization=authorization;this.token=token;
        this.jwks=jwks;this.clientId=clientId;this.clientSecret=clientSecret;this.redirect=redirect;
        if(enabled) {
            for(String value: List.of(issuer,authorization,token,jwks,redirect)) {
                URI uri=URI.create(value);
                boolean local=List.of("localhost","127.0.0.1","[::1]").contains(String.valueOf(uri.getHost()));
                if (uri.getHost()==null || uri.getUserInfo()!=null || uri.getFragment()!=null || uri.getQuery()!=null ||
                        !("https".equals(uri.getScheme()) || (local && "http".equals(uri.getScheme()))))
                    throw new IllegalArgumentException("OIDC endpoints require HTTPS or loopback HTTP");
            }
            if (issuer.length()>1024 || clientId.isBlank() || clientSecret.isBlank()) throw new IllegalArgumentException("OIDC client registration missing");
        }
    }
    String origin() { URI u=URI.create(redirect);return u.getScheme()+"://"+u.getRawAuthority(); }
}
