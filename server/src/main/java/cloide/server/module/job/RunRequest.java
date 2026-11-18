package cloide.server.module.job;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record RunRequest(@Positive long serverId,
        @NotNull @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{0,63}") String repository,
        @NotNull @Pattern(regexp = "[0-9a-f]{40}") String commitSha, @NotNull UUID requestId) { }
