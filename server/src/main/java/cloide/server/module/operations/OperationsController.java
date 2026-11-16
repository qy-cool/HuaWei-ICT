package cloide.server.module.operations;

import cloide.server.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/projects/{projectId}/operations")
public class OperationsController {
    public record Request(@NotNull UUID requestId, @NotNull @Pattern(regexp="rollback|repair|confirm_cause") String action,
                          @NotNull UUID releaseId, @Positive Long buildNode, @NotBlank @Size(max=1200) String rationale) {}
    private final OperationsService operations;

    @GetMapping("/topology")
    public ApiResponse<List<OperationsService.ServiceNode>> topology(@PathVariable long projectId) {
        return ApiResponse.success(operations.topology(projectId));
    }

    @GetMapping("/alerts")
    public ApiResponse<List<OperationsService.Alert>> alerts(@PathVariable long projectId,
            @RequestParam(defaultValue="0") long before,@RequestParam(defaultValue="50") int limit) {
        return ApiResponse.success(operations.alerts(projectId,before,limit));
    }

    @GetMapping("/alerts/{alertId}")
    public ApiResponse<OperationsService.Detail> detail(@PathVariable long projectId,@PathVariable long alertId) {
        return ApiResponse.success(operations.detail(projectId,alertId));
    }

    @PostMapping("/alerts/{alertId}/actions")
    public ApiResponse<OperationsService.Action> act(@PathVariable long projectId,@PathVariable long alertId,
                                                    @Valid @RequestBody Request body) {
        return ApiResponse.success(operations.act(projectId,alertId,body));
    }
}
