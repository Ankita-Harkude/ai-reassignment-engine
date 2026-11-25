package com.ziprun.reassignment.ai;

import com.ziprun.reassignment.domain.AgentStatus;
import com.ziprun.reassignment.routing.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RoutingPromptFactoryTest {

    private final RoutingPromptFactory factory = new RoutingPromptFactory();
    private final OrderSnapshot order = new OrderSnapshot("ORD-001", "Electronics - Koramangala", "AGT-001", "Priya Sharma");
    private final List<AgentSnapshot> roster = List.of(
            new AgentSnapshot("AGT-002", "Rahul Verma", AgentStatus.AVAILABLE, 0, 1));

    @Test
    void initialPromptIsARoutineReviewWithNoIncidentLanguage() {
        String prompt = factory.build(new RoutingRequest(order, roster, RoutingContext.initial()));

        assertThat(prompt).contains("ROUTINE REASSIGNMENT REVIEW", "ORD-001", "AGT-002 | Rahul Verma | AVAILABLE | 0 | 1 | 1");
        assertThat(prompt).doesNotContain("OFFLINE", "stranded", "RECOVERY");
    }

    @Test
    void replanPromptIsAnIncidentReportNamingTheFailureAndStrandedOrders() {
        var ctx = RoutingContext.agentOffline("AGT-001", "Priya Sharma", List.of("ORD-001", "ORD-002", "ORD-008"));

        String prompt = factory.build(new RoutingRequest(order, roster, ctx));

        assertThat(prompt).contains("AGENT OFFLINE - RECOVERY RE-PLAN", "Priya Sharma", "AGT-001",
                "3 order(s)", "ORD-001, ORD-002, ORD-008", "VOID", "in parallel");
        assertThat(prompt).doesNotContain("ROUTINE REASSIGNMENT REVIEW");
    }

    @Test
    void bothPromptsDemandStrictJsonOutput() {
        var initial = factory.build(new RoutingRequest(order, roster, RoutingContext.initial()));
        var replan = factory.build(new RoutingRequest(order, roster,
                RoutingContext.agentOffline("AGT-001", "Priya Sharma", List.of("ORD-001"))));

        assertThat(initial).contains("\"agentId\"", "\"confidence\"", "\"reasoning\"");
        assertThat(replan).contains("\"agentId\"", "\"confidence\"", "\"reasoning\"");
    }
}
