package cloide.server.module.job;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.OffsetDateTime;
import java.util.Set;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ProjectRun(String jobId, long projectId, long serverId, @JsonIgnore long createdBy,
        String repository, String commitSha, String status, Integer exitCode, String output,
        OffsetDateTime createdAt) {
    static final Set<String> TERMINAL = Set.of("succeeded", "failed", "cancelled", "uncertain");
}
