package com.ziprun.reassignment.dto;

import com.ziprun.reassignment.domain.AgentStatus;
import com.ziprun.reassignment.domain.SuggestionStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request bodies, grouped in one file because each is small. Validation lives here, at the edge:
 * controllers use @Valid, so a bad body is rejected with a 400 ProblemDetail before any service runs.
 * Enum fields are validated by JSON binding itself (an unknown value is a 400 MALFORMED_REQUEST).
 */
public final class Requests {
    private Requests() {
    }

    public record CreateOrder(@NotBlank @Size(max = 255) String description,
                              @NotBlank @Size(max = 64) String assignedAgentId) {
    }

    public record UpdateAgentStatus(@NotNull AgentStatus status) {
    }

    public record UpdateSuggestion(@NotNull SuggestionStatus status) {
    }

    public record SelectStrategy(@NotBlank @Size(max = 64) String strategy) {
    }
}
