package cloide.server.module.auth.oidc;

import cloide.server.common.ApiResponse;
import cloide.server.security.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/oidc/mappings")
@RequiredArgsConstructor
public class OidcMappingController {
    private final OidcAccounts accounts;
    public record Binding(@NotBlank @Size(max=255) String subject) {}
    @PutMapping("/{userId}")
    public ApiResponse<Void> put(@PathVariable long userId,@Valid @RequestBody Binding body,
            @AuthenticationPrincipal AuthenticatedUser admin){
        accounts.bind(userId,body.subject(),admin.id());
        return ApiResponse.success(null);
    }
    @DeleteMapping("/{userId}")
    public ApiResponse<Void> delete(@PathVariable long userId){
        accounts.unbind(userId);
        return ApiResponse.success(null);
    }
}
