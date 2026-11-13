package cloide.server.module.project.controller;

import cloide.server.common.ApiResponse;
import cloide.server.module.project.service.NodeCapabilities;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects/{projectId}/nodes/{serverId}")
@RequiredArgsConstructor
public class NodeCapabilitiesController {
    private final NodeCapabilities capabilities;
    @GetMapping("/capabilities")
    public ApiResponse<NodeCapabilities.Snapshot> read(@PathVariable long projectId,@PathVariable long serverId) {
        return ApiResponse.success(capabilities.get(projectId,serverId));
    }
}
