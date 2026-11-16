package cloide.server.module.auth.oidc;

import cloide.server.common.ApiResponse;
import cloide.server.common.BusinessException;
import cloide.server.common.ClientIpResolver;
import cloide.server.common.ErrorCode;
import cloide.server.module.auth.limit.LoginRateLimiter;
import cloide.server.module.auth.vo.LoginVo;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth/oidc")
@RequiredArgsConstructor
public class OidcController {
    private static final String COOKIE="__Host-cloide-oidc";
    private final OidcSettings settings;
    private final OidcState state;
    private final OidcIdentityProvider provider;
    private final OidcAccounts accounts;
    private final ObjectProvider<LoginRateLimiter> limiter;
    private final ClientIpResolver addresses;
    public record Exchange(@NotBlank @Size(max=2048) String code,@NotBlank @Size(max=64) String state) {}

    @GetMapping("/status")
    public ApiResponse<Map<String,Boolean>> status(){return ApiResponse.success(Map.of("enabled",settings.enabled));}

    @PostMapping("/start")
    public ApiResponse<Map<String,String>> start(HttpServletRequest request,HttpServletResponse response){
        requireOrigin(request);
        limiter.ifAvailable(l->l.checkAttempt(addresses.resolve(request)));
        var attempt=state.begin();
        cookie(response,attempt.verifier(),300);
        return ApiResponse.success(Map.of("authorizationUrl",provider.authorizationUrl(attempt)));
    }

    @PostMapping("/complete")
    public ApiResponse<LoginVo> complete(@Valid @RequestBody Exchange exchange,
            @CookieValue(value=COOKIE,required=false) String verifier,HttpServletRequest request,HttpServletResponse response){
        requireOrigin(request);
        String nonce=state.consume(exchange.state(),verifier);
        cookie(response,"",0);
        return ApiResponse.success(accounts.login(provider.exchange(exchange.code(),verifier,nonce)));
    }
    private void requireOrigin(HttpServletRequest request){
        if(!settings.enabled)throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        if(!settings.origin().equals(request.getHeader("Origin")))throw new BusinessException(ErrorCode.FORBIDDEN);
    }
    private static void cookie(HttpServletResponse response,String value,long age){
        response.setHeader(HttpHeaders.CACHE_CONTROL,"no-store");
        response.addHeader(HttpHeaders.SET_COOKIE,ResponseCookie.from(COOKIE,value)
                .secure(true).httpOnly(true).path("/").sameSite("Lax").maxAge(age).build().toString());
    }
}
