package com.ziprun.reassignment.domain;

import com.ziprun.reassignment.exception.InvalidTransitionException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** State-machine rules live on the entities, so they are testable without Spring or a database. */
class DomainStateMachineTest {

    @Nested
    class AgentLoad {

        @Test
        void takingAnOrderMakesAnAvailableAgentBusy() {
            var agent = new Agent("A1", "Ann", AgentStatus.AVAILABLE, 0);
            agent.takeOrder();
            assertThat(agent.getActiveOrderCount()).isEqualTo(1);
            assertThat(agent.getStatus()).isEqualTo(AgentStatus.BUSY);
        }

        @Test
        void takingAnOrderNeverBringsAnOfflineAgentBackOnline() {
            var agent = new Agent("A1", "Ann", AgentStatus.OFFLINE, 2);
            agent.takeOrder();
            assertThat(agent.getStatus()).isEqualTo(AgentStatus.OFFLINE);
            assertThat(agent.getActiveOrderCount()).isEqualTo(3);
        }

        @Test
        void releasingTheLastOrderMakesABusyAgentAvailable() {
            var agent = new Agent("A1", "Ann", AgentStatus.BUSY, 1);
            agent.releaseOrder();
            assertThat(agent.getActiveOrderCount()).isZero();
            assertThat(agent.getStatus()).isEqualTo(AgentStatus.AVAILABLE);
        }

        @Test
        void releasingWhileOtherOrdersRemainKeepsAgentBusy() {
            var agent = new Agent("A1", "Ann", AgentStatus.BUSY, 3);
            agent.releaseOrder();
            assertThat(agent.getActiveOrderCount()).isEqualTo(2);
            assertThat(agent.getStatus()).isEqualTo(AgentStatus.BUSY);
        }

        @Test
        void loadNeverGoesNegative() {
            var agent = new Agent("A1", "Ann", AgentStatus.AVAILABLE, 0);
            agent.releaseOrder();
            agent.releaseOrder();
            assertThat(agent.getActiveOrderCount()).isZero();
        }

        @Test
        void releasingTheLastOrderOfAnOfflineAgentKeepsItOffline() {
            var agent = new Agent("A1", "Ann", AgentStatus.OFFLINE, 1);
            agent.releaseOrder();
            assertThat(agent.getActiveOrderCount()).isZero();
            assertThat(agent.getStatus()).isEqualTo(AgentStatus.OFFLINE);
        }
    }

    @Nested
    class OrderLifecycle {

        private final Agent first = new Agent("A1", "Ann", AgentStatus.BUSY, 1);
        private final Agent second = new Agent("A2", "Bob", AgentStatus.AVAILABLE, 0);
        private final Order order = new Order("ORD-1", "Test", first);

        @Test
        void newOrderStartsAssigned() {
            assertThat(order.getStatus()).isEqualTo(OrderStatus.ASSIGNED);
            assertThat(order.getAssignedAgent()).isSameAs(first);
        }

        @Test
        void happyPathAssignedToPendingToReassigned() {
            order.markReassignmentPending();
            order.reassignTo(second);

            assertThat(order.getStatus()).isEqualTo(OrderStatus.REASSIGNED);
            assertThat(order.getAssignedAgent()).isSameAs(second);
        }

        @Test
        void markingPendingTwiceIsIdempotent() {
            order.markReassignmentPending();
            order.markReassignmentPending();
            assertThat(order.getStatus()).isEqualTo(OrderStatus.REASSIGNMENT_PENDING);
        }

        @Test
        void cannotReassignAnOrderThatIsNotPending() {
            assertThatThrownBy(() -> order.reassignTo(second)).isInstanceOf(InvalidTransitionException.class);
            assertThat(order.getAssignedAgent()).isSameAs(first);   // unchanged after the failed attempt
        }

        @Test
        void cannotReassignTwiceInARow() {
            order.markReassignmentPending();
            order.reassignTo(second);
            assertThatThrownBy(() -> order.reassignTo(first)).isInstanceOf(InvalidTransitionException.class);
        }

        @Test
        void aReassignedOrderCanBecomePendingAgainIfItsNewAgentDropsOut() {
            order.markReassignmentPending();
            order.reassignTo(second);

            order.markReassignmentPending();

            assertThat(order.getStatus()).isEqualTo(OrderStatus.REASSIGNMENT_PENDING);
        }

        @Test
        void aDeliveredOrderCannotBeReplanned() {
            ReflectionTestUtils.setField(order, "status", OrderStatus.DELIVERED);

            assertThatThrownBy(order::markReassignmentPending).isInstanceOf(InvalidTransitionException.class);
            assertThatThrownBy(() -> order.reassignTo(second)).isInstanceOf(InvalidTransitionException.class);
        }
    }

    @Nested
    class SuggestionLifecycle {

        private final Agent holder = new Agent("A1", "Ann", AgentStatus.OFFLINE, 1);
        private final Agent target = new Agent("A2", "Bob", AgentStatus.AVAILABLE, 0);
        private final Order order = new Order("ORD-1", "Test", holder);

        private ReassignmentSuggestion pending(TriggerReason trigger) {
            return new ReassignmentSuggestion(order, target, 0.8, "because", trigger,
                    RecommendationSource.RULE_BASED, null);
        }

        @Test
        void newSuggestionIsPendingWithAnOpenKeyPerTriggerAndOrder() {
            var s = pending(TriggerReason.AGENT_OFFLINE);

            assertThat(s.getStatus()).isEqualTo(SuggestionStatus.PENDING);
            assertThat(s.getResolvedAt()).isNull();
            assertThat(ReassignmentSuggestion.keyFor(TriggerReason.AGENT_OFFLINE, "ORD-1"))
                    .isEqualTo("AGENT_OFFLINE:ORD-1");
        }

        @Test
        void differentTriggersForTheSameOrderDoNotCollide() {
            assertThat(ReassignmentSuggestion.keyFor(TriggerReason.INITIAL, "ORD-1"))
                    .isNotEqualTo(ReassignmentSuggestion.keyFor(TriggerReason.AGENT_OFFLINE, "ORD-1"));
        }

        @Test
        void acceptingResolvesAndReleasesTheKeySoALaterSuggestionIsAllowed() {
            var s = pending(TriggerReason.AGENT_OFFLINE);
            s.accept();

            assertThat(s.getStatus()).isEqualTo(SuggestionStatus.ACCEPTED);
            assertThat(s.getResolvedAt()).isNotNull();
            assertThat(org.springframework.test.util.ReflectionTestUtils.getField(s, "activeKey")).isNull();
        }

        @Test
        void rejectingResolvesToo() {
            var s = pending(TriggerReason.INITIAL);
            s.reject();
            assertThat(s.getStatus()).isEqualTo(SuggestionStatus.REJECTED);
        }

        @Test
        void aResolvedSuggestionCannotBeResolvedAgainInEitherDirection() {
            var accepted = pending(TriggerReason.INITIAL);
            accepted.accept();
            assertThatThrownBy(accepted::accept).isInstanceOf(InvalidTransitionException.class);
            assertThatThrownBy(accepted::reject).isInstanceOf(InvalidTransitionException.class);

            var rejected = pending(TriggerReason.INITIAL);
            rejected.reject();
            assertThatThrownBy(rejected::accept).isInstanceOf(InvalidTransitionException.class);
            assertThat(rejected.getStatus()).isEqualTo(SuggestionStatus.REJECTED);   // unchanged
        }
    }
}
