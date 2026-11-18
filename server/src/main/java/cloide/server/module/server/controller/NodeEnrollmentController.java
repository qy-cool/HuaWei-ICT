package cloide.server.module.server.controller;

import cloide.server.common.ApiResponse;
import cloide.server.module.server.service.NodeEnrollmentService;
import cloide.server.module.server.vo.AgentTokenVo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class NodeEnrollmentController {
    private final NodeEnrollmentService enrollment;

    public record InviteRequest(@NotBlank @Size(max = 100) String name) {}
    public record Ticket(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}") String ticket) {
        @Override public String toString() { return "Ticket[redacted]"; }
    }

    @PostMapping("/admin/node-enrollments")
    public ApiResponse<Ticket> invite(@Valid @RequestBody InviteRequest request) {
        return ApiResponse.success(new Ticket(enrollment.invite(request.name())));
    }

    @PostMapping("/node-enrollments/redeem")
    public ApiResponse<AgentTokenVo> redeem(@Valid @RequestBody Ticket request) {
        return ApiResponse.success(enrollment.redeem(request.ticket()));
    }
}
