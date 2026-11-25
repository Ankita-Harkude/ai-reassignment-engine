package com.ziprun.reassignment.ai;

import com.ziprun.reassignment.domain.TriggerReason;
import com.ziprun.reassignment.routing.AgentSnapshot;
import com.ziprun.reassignment.routing.RoutingRequest;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Builds the two prompts. They are deliberately different documents, not one template with a flag:
 *  - initial:  a routine "who should carry this order" review.
 *  - re-plan:  an incident report - who failed, how many orders are stranded, that old assignments are
 *              void, and that sibling orders are being re-planned in parallel (so spread the load).
 */
@Component
public class RoutingPromptFactory {

    private static final String OUTPUT_FORMAT = """
            Respond with ONLY one JSON object, no markdown, no extra text:
            {"agentId":"<exactly one id from the roster>","confidence":<number from 0.0 to 1.0>,"reasoning":"<2-3 plain-English sentences an ops manager can act on>"}""";

    public String build(RoutingRequest request) {
        return request.context().trigger() == TriggerReason.AGENT_OFFLINE
                ? replanPrompt(request)
                : initialPrompt(request);
    }

    String initialPrompt(RoutingRequest r) {
        return """
                You are the dispatch assistant for ZipRun, a city delivery fleet.

                SITUATION: ROUTINE REASSIGNMENT REVIEW
                An ops manager asked for a second opinion on who should carry one order. Nothing has failed;
                this is an optimisation request. The order is currently held by %s (%s).

                ORDER
                - id: %s
                - description: %s

                AGENT ROSTER (eligible agents only)
                %s

                HOW TO DECIDE
                - Choose exactly one agent from the roster, using the id exactly as written.
                - Prefer the lowest projected load (active orders + already-queued suggestions).
                - At equal load prefer AVAILABLE over BUSY.
                - Confidence is your honest certainty: high only when one agent is clearly the best fit.

                %s
                """.formatted(r.order().currentAgentName(), r.order().currentAgentId(),
                r.order().id(), r.order().description(), roster(r), OUTPUT_FORMAT);
    }

    String replanPrompt(RoutingRequest r) {
        var c = r.context();
        return """
                You are the dispatch assistant for ZipRun, a city delivery fleet.

                SITUATION REPORT: AGENT OFFLINE - RECOVERY RE-PLAN
                Agent %s (%s) has just gone OFFLINE and cannot complete any delivery. %d order(s) assigned to
                them are now stranded: %s. Every previous assignment to %s is VOID and %s must not be chosen.
                This is time-sensitive recovery work, not routine optimisation.

                You are re-planning ONE stranded order now. The other stranded orders are being re-planned
                in parallel, each by a separate request, so do not send everything to the same person:
                the "queued" column shows suggestions already waiting for ops approval, which are NOT yet
                reflected in "active".

                STRANDED ORDER
                - id: %s
                - description: %s

                AGENT ROSTER (online agents only, failed agent excluded)
                %s

                HOW TO DECIDE
                - Choose exactly one agent from the roster, using the id exactly as written.
                - Spread the recovery load: prefer the lowest projected load (active + queued).
                - At equal load prefer AVAILABLE over BUSY.
                - In "reasoning", state plainly that this is a recovery from %s going offline and why this
                  agent is best placed to absorb it. If every agent is heavily loaded, say so and lower confidence.
                - Ops will approve or reject your suggestion; nothing is assigned until they do.

                %s
                """.formatted(c.failedAgentName(), c.failedAgentId(), c.strandedOrderCount(),
                String.join(", ", c.strandedOrderIds()), c.failedAgentName(), c.failedAgentName(),
                r.order().id(), r.order().description(), roster(r), c.failedAgentName(), OUTPUT_FORMAT);
    }

    private static String roster(RoutingRequest r) {
        StringBuilder sb = new StringBuilder("id | name | status | active | queued | projected load\n");
        for (AgentSnapshot a : r.candidates()) {
            sb.append(String.format(Locale.ROOT, "%s | %s | %s | %d | %d | %d%n",
                    a.id(), a.name(), a.status(), a.activeOrders(), a.pendingIncoming(), a.projectedLoad()));
        }
        return sb.toString().stripTrailing();
    }
}
