package com.ziprun.reassignment.routing;

import java.util.List;

/** What a strategy receives: the order, the already-eligible candidates, and the situation. */
public record RoutingRequest(OrderSnapshot order, List<AgentSnapshot> candidates, RoutingContext context) {
}
