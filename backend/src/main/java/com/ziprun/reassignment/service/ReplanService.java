package com.ziprun.reassignment.service;

import com.ziprun.reassignment.domain.Agent;
import com.ziprun.reassignment.domain.Order;
import com.ziprun.reassignment.domain.OrderStatus;
import com.ziprun.reassignment.exception.NoEligibleAgentException;
import com.ziprun.reassignment.repository.AgentRepository;
import com.ziprun.reassignment.repository.OrderRepository;
import com.ziprun.reassignment.routing.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * The REASON + ACT steps of the agentic loop: work out which orders are stranded, then queue one
 * suggestion per order. It never assigns anything - the ACCEPT click is the human checkpoint.
 *
 * {@link #replanOrders} is trigger-agnostic: a sprint-3 SLA monitor can call it with its own
 * RoutingContext without touching the OFFLINE path.
 */
@Service
public class ReplanService {

    private static final Logger log = LoggerFactory.getLogger(ReplanService.class);

    /** Orders still the agent's responsibility. DELIVERED orders are done and never re-planned. */
    private static final Set<OrderStatus> REPLANNABLE =
            Set.of(OrderStatus.ASSIGNED, OrderStatus.REASSIGNED, OrderStatus.REASSIGNMENT_PENDING);

    private final OrderRepository orders;
    private final AgentRepository agents;
    private final SuggestionService suggestions;
    private final OrderService orderService;

    public ReplanService(OrderRepository orders, AgentRepository agents, SuggestionService suggestions,
                         OrderService orderService) {
        this.orders = orders;
        this.agents = agents;
        this.suggestions = suggestions;
        this.orderService = orderService;
    }

    public void replanForOfflineAgent(String agentId) {
        Agent agent = agents.findById(agentId).orElse(null);
        if (agent == null) {
            log.warn("Re-plan skipped: agent {} no longer exists", agentId);
            return;
        }
        List<String> stranded = orders.findByAssignedAgent_IdAndStatusInOrderByIdAsc(agentId, REPLANNABLE)
                .stream().map(Order::getId).toList();
        if (stranded.isEmpty()) {
            log.info("Agent {} went offline with no active orders - nothing to re-plan", agentId);
            return;
        }
        replanOrders(stranded, RoutingContext.agentOffline(agent.getId(), agent.getName(), stranded));
    }

    public void replanOrders(List<String> orderIds, RoutingContext context) {
        int created = 0, existing = 0, failed = 0;
        for (String orderId : orderIds) {
            try {
                // Idempotent: returns the already-open suggestion instead of creating a duplicate.
                if (suggestions.suggest(orderId, context).created()) created++; else existing++;
            } catch (NoEligibleAgentException e) {
                failed++;
                log.warn("Order {} cannot be re-planned: {}", orderId, e.getMessage());
                // Never leave it invisible: the order is affected even though nobody can take it right now.
                // It shows up in ops' "pending with no open suggestion" list, and a suggestion can be
                // requested again once an agent is available.
                flagAsPending(orderId);
            } catch (RuntimeException e) {
                // Isolate failures per order: one bad order must not strand the others.
                failed++;
                log.error("Re-plan of order {} failed", orderId, e);
            }
        }
        log.info("Re-plan ({}) finished: {} created, {} already pending, {} failed",
                context.trigger(), created, existing, failed);
    }

    private void flagAsPending(String orderId) {
        try {
            orderService.markReassignmentPending(orderId);
        } catch (RuntimeException e) {
            log.error("Could not flag order {} as awaiting reassignment", orderId, e);
        }
    }
}
