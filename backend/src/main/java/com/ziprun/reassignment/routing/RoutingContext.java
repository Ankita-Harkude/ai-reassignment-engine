package com.ziprun.reassignment.routing;

import com.ziprun.reassignment.domain.TriggerReason;

import java.util.List;

/**
 * The situation the router is in. INITIAL is a plain first-assignment review; AGENT_OFFLINE carries the
 * incident: who failed and which orders are stranded - this is what the AI re-plan prompt is built from.
 */
public record RoutingContext(TriggerReason trigger, String failedAgentId, String failedAgentName,
                              int strandedOrderCount, List<String> strandedOrderIds) {

    public static RoutingContext initial() {
        return new RoutingContext(TriggerReason.INITIAL, null, null, 0, List.of());
    }

    public static RoutingContext agentOffline(String agentId, String agentName, List<String> strandedOrderIds) {
        return new RoutingContext(TriggerReason.AGENT_OFFLINE, agentId, agentName,
                strandedOrderIds.size(), List.copyOf(strandedOrderIds));
    }
}
