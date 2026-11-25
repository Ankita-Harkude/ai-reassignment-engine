package com.ziprun.reassignment.routing;

import com.ziprun.reassignment.domain.AgentStatus;

/**
 * An agent as the router sees it. {@code pendingIncoming} is the number of PENDING suggestions already
 * recommending this agent, so a bulk re-plan spreads orders instead of piling them on one person.
 */
public record AgentSnapshot(String id, String name, AgentStatus status, int activeOrders, int pendingIncoming) {
    public int projectedLoad() {
        return activeOrders + pendingIncoming;
    }
}
