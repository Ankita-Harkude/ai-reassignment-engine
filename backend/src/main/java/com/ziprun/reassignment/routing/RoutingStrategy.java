package com.ziprun.reassignment.routing;

import java.util.List;

/**
 * The routing contract. Pure: takes snapshots in, returns ranked recommendations out - no JPA entities,
 * no transactions, no HTTP. That is what lets the same strategy be called from the REST endpoint and from
 * the async re-plan listener, and from a future SLA monitor, without modification.
 *
 * Implementations are Spring beans whose bean NAME is the strategy key (e.g. "rule-based", "ai").
 * Adding a strategy (sprint 2: ZoneAffinityStrategy) = implement this + annotate @Component("zone-affinity").
 *
 * Contract: candidates are already filtered for eligibility; return best-first; never return an agent
 * that is not in request.candidates().
 */
public interface RoutingStrategy {
    List<Recommendation> recommend(RoutingRequest request);
}
