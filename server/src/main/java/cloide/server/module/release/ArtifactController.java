package cloide.server.module.release;

import cloide.server.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/projects/{projectId}/artifacts")
public class ArtifactController {
    private final ArtifactService artifacts;

    @PostMapping
    public ApiResponse<ArtifactService.Artifact> upload(@PathVariable long projectId, @RequestParam UUID runId,
            @RequestParam String kind, @RequestHeader("X-Content-SHA256") String sha, HttpServletRequest request)
            throws IOException {
        return ApiResponse.success(artifacts.upload(projectId, runId.toString(), kind, sha, request.getInputStream()));
    }

    @GetMapping
    public ApiResponse<List<ArtifactService.Artifact>> list(@PathVariable long projectId) {
        return ApiResponse.success(artifacts.list(projectId));
    }

    @GetMapping("/{artifactId}/content")
    public ResponseEntity<InputStreamResource> content(@PathVariable long projectId, @PathVariable UUID artifactId)
            throws IOException {
        return artifacts.download(projectId, artifactId.toString());
    }
}
