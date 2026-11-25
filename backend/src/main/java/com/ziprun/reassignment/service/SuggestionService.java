package com.ziprun.reassignment.service;

import com.ziprun.reassignment.domain.*;
import com.ziprun.reassignment.dto.SuggestionView;
import com.ziprun.reassignment.exception.InvalidTransitionException;
import com.ziprun.reassignment.exception.NotFoundException;
import com.ziprun.reassignment.repository.AgentRepository;
import com.ziprun.reassignment.repository.OrderRepository;
import com.ziprun.reassignment.repository.SuggestionRepository;
import com.ziprun.reassignment.routing.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates suggestions: load a snapshot -> ask the RoutingEngine -> persist. It deliberately holds
 * NO routing logic (that is the RoutingEngine's job) and NO database transaction while the router runs,
 * so a slow LLM call never pins a connection.
 */
@Service
public class SuggestionService {

    /** created=false means an equivalent PENDING suggestion already existed and was returned as-is. */
    public record Outcome(SuggestionView suggestion, boolean created) {
    }

    private record Prepared(SuggestionView existing, OrderSnapshot order, List<AgentSnapshot> roster) {
    }

    private final SuggestionRepository suggestions;
    private final OrderRepository orders;
    private final AgentRepository agents;
    private final RoutingEngine routing;
    private final TransactionTemplate readTx;
    private final TransactionTemplate writeTx;

    public SuggestionService(SuggestionRepository suggestions, OrderRepository orders, AgentRepository agents,
                             RoutingEngine routing, PlatformTransactionManager txManager) {
        this.suggestions = suggestions;
        this.orders = orders;
        this.agents = agents;
        this.routing = routing;
        this.readTx = new TransactionTemplate(txManager);
        this.readTx.setReadOnly(true);
        this.writeTx = new TransactionTemplate(txManager);
    }

    /**
     * Creates (or returns the existing) PENDING suggestion for an order. Idempotent per (trigger, order):
     * checked up front for the fast path and enforced by a unique constraint for races.
     * Called by both the REST endpoint (INITIAL) and the async re-plan (AGENT_OFFLINE).
     */
    public Outcome suggest(String orderId, RoutingContext context) {
        Prepared prepared = readTx.execute(tx -> prepare(orderId, context));
        if (prepared.existing() != null) {
            return new Outcome(prepared.existing(), false);
        }

        // No transaction is open here: this is where the (possibly slow) strategy runs.
        Recommendation best = routing.route(prepared.order(), prepared.roster(), context).get(0);

        try {
            SuggestionView saved = writeTx.execute(tx -> persist(orderId, best, context));
            return new Outcome(saved, true);
        } catch (DataIntegrityViolationException | ObjectOptimisticLockingFailureException raced) {
            // Another thread created the same open suggestion between our check and our insert: either the
            // unique key rejected our row, or it had already moved the order (version conflict).
            SuggestionView existing = readTx.execute(tx -> suggestions
                    .findByActiveKey(ReassignmentSuggestion.keyFor(context.trigger(), orderId))
                    .map(SuggestionView::from).orElseThrow(() -> raced));
            return new Outcome(existing, false);
        }
    }

    private Prepared prepare(String orderId, RoutingContext context) {
        Order order = orders.findWithAgentById(orderId).orElseThrow(() -> new NotFoundException("Order", orderId));
        if (order.getStatus() == OrderStatus.DELIVERED) {
            throw new InvalidTransitionException("Order " + orderId + " is already DELIVERED");
        }
        var existing = suggestions.findByActiveKey(ReassignmentSuggestion.keyFor(context.trigger(), orderId));
        if (existing.isPresent()) {
            return new Prepared(SuggestionView.from(existing.get()), null, null);
        }
        Map<String, Integer> queued = new HashMap<>();
        for (Object[] row : suggestions.countPendingByRecommendedAgent()) {
            queued.put((String) row[0], ((Long) row[1]).intValue());
        }
        List<AgentSnapshot> roster = agents.findAllByOrderByIdAsc().stream()
                .map(a -> new AgentSnapshot(a.getId(), a.getName(), a.getStatus(),
                        a.getActiveOrderCount(), queued.getOrDefault(a.getId(), 0)))
                .toList();
        Agent current = order.getAssignedAgent();
        return new Prepared(null,
                new OrderSnapshot(order.getId(), order.getDescription(), current.getId(), current.getName()), roster);
    }

    private SuggestionView persist(String orderId, Recommendation rec, RoutingContext context) {
        Order order = orders.findWithAgentById(orderId).orElseThrow(() -> new NotFoundException("Order", orderId));
        Agent recommended = agents.findById(rec.agentId()).orElseThrow(() -> new NotFoundException("Agent", rec.agentId()));
        order.markReassignmentPending();
        ReassignmentSuggestion s = new ReassignmentSuggestion(order, recommended,
                Math.min(1.0, Math.max(0.0, rec.confidence())), rec.reasoning(),
                context.trigger(), rec.source(), rec.fallbackReason());
        return SuggestionView.from(suggestions.saveAndFlush(s));
    }

    @Transactional(readOnly = true)
    public List<SuggestionView> list(SuggestionStatus status) {
        List<ReassignmentSuggestion> found = status == null
                ? suggestions.findAllByOrderByCreatedAtDesc()
                : suggestions.findByStatusOrderByCreatedAtDesc(status);
        return found.stream().map(SuggestionView::from).toList();
    }

    /** The human checkpoint: only here does an order actually change hands. */
    @Transactional
    public SuggestionView resolve(Long id, SuggestionStatus target) {
        if (target == SuggestionStatus.PENDING) {
            throw new IllegalArgumentException("status must be ACCEPTED or REJECTED");
        }
        ReassignmentSuggestion s = suggestions.findWithDetailsById(id)
                .orElseThrow(() -> new NotFoundException("Suggestion", id));
        if (target == SuggestionStatus.REJECTED) {
            s.reject();   // order stays REASSIGNMENT_PENDING so ops can request another suggestion
            return SuggestionView.from(s);
        }
        return accept(s);
    }

    private SuggestionView accept(ReassignmentSuggestion s) {
        Order order = s.getOrder();
        Agent target = s.getRecommendedAgent();
        if (target.getStatus() == AgentStatus.OFFLINE) {
            throw new InvalidTransitionException(
                    "Recommended agent " + target.getId() + " is now OFFLINE; reject this and request a new suggestion");
        }
        Agent previous = order.getAssignedAgent();

        s.accept();                     // throws 409 if it was already resolved
        order.reassignTo(target);       // throws 409 unless the order is REASSIGNMENT_PENDING
        previous.releaseOrder();
        target.takeOrder();

        // Any other open suggestion for this order is now obsolete.
        for (ReassignmentSuggestion other : suggestions.findByOrder_IdAndStatus(order.getId(), SuggestionStatus.PENDING)) {
            if (!other.getId().equals(s.getId())) {
                other.reject();
            }
        }
        return SuggestionView.from(s);
    }
}
