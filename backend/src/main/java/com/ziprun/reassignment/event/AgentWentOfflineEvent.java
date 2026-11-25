package com.ziprun.reassignment.event;

import java.time.Instant;

/** Published once, only on a real transition into OFFLINE. The trigger of the agentic loop. */
public record AgentWentOfflineEvent(String agentId, String agentName, Instant occurredAt) {
}
