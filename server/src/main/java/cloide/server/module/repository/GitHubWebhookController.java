package cloide.server.module.repository;

import cloide.server.common.*;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/integrations/github")
public class GitHubWebhookController {
    private final RepositoryService service;
    @PostMapping("/webhook")
    public ApiResponse<Void> receive(@RequestHeader("X-GitHub-Delivery") UUID delivery,
            @RequestHeader("X-GitHub-Event") String type,@RequestHeader(value="X-Hub-Signature-256", required=false) String signature,
            HttpServletRequest request) throws IOException {
        int limit=2*1024*1024;
        byte[] raw=request.getInputStream().readNBytes(limit+1);
        if (raw.length>limit) throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        service.webhook(delivery,type,signature,raw);
        return ApiResponse.success(null);
    }
}
