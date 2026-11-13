package cloide.server.module.coding;

import cloide.server.common.ApiResponse;
import cloide.server.websocket.AgentAuthenticationService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/coding-agent")
@RequiredArgsConstructor
public class CodingNodeController {
    @org.springframework.beans.factory.annotation.Value("${cloide.engineering.recovery:false}")
    private boolean recovering;
    private final AgentAuthenticationService authentication;
    private final CodingService service;

    public record Poll(@NotNull UUID workerId, @Positive long projectId,
            @NotNull @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9._-]{0,63}") String repository, UUID taskId) {}
    public record Receipt(@NotNull UUID workerId, @NotNull UUID taskId, @Positive @Max(4096) int seq,
            @NotNull JsonNode event) {}

    @PostMapping("/poll")
    public ApiResponse<Map<String,Object>> poll(@RequestHeader("X-Node-Id") long node,
            @RequestHeader("X-Node-Token") String token, @Valid @RequestBody Poll body) {
        authentication.authenticate(node,token);
        if (recovering) return ApiResponse.success(Map.of("action", body.taskId() == null ? "idle" : "cancel"));
        return ApiResponse.success(service.poll(node, body));
    }

    @PostMapping("/events")
    public ApiResponse<Integer> event(@RequestHeader("X-Node-Id") long node,
            @RequestHeader("X-Node-Token") String token, @Valid @RequestBody Receipt body) {
        authentication.authenticate(node,token);
        return ApiResponse.success(service.record(node,body));
    }
}
