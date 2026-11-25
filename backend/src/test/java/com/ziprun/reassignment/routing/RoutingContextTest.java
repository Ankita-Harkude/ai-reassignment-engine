package com.ziprun.reassignment.routing;

import com.ziprun.reassignment.domain.TriggerReason;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoutingContextTest {

    @Test
    void initialContextHasNoIncident() {
        var ctx = RoutingContext.initial();

        assertThat(ctx.trigger()).isEqualTo(TriggerReason.INITIAL);
        assertThat(ctx.failedAgentId()).isNull();
        assertThat(ctx.strandedOrderCount()).isZero();
        assertThat(ctx.strandedOrderIds()).isEmpty();
    }

    @Test
    void offlineContextCountsStrandedOrders() {
        var ctx = RoutingContext.agentOffline("AGT-1", "Priya", List.of("ORD-1", "ORD-2", "ORD-3"));

        assertThat(ctx.trigger()).isEqualTo(TriggerReason.AGENT_OFFLINE);
        assertThat(ctx.strandedOrderCount()).isEqualTo(3);
    }

    @Test
    void offlineContextIsAnImmutableSnapshotOfTheOrderList() {
        var mutable = new ArrayList<>(List.of("ORD-1"));
        var ctx = RoutingContext.agentOffline("AGT-1", "Priya", mutable);

        mutable.add("ORD-2");

        assertThat(ctx.strandedOrderIds()).containsExactly("ORD-1");
        assertThatThrownBy(() -> ctx.strandedOrderIds().add("ORD-9")).isInstanceOf(UnsupportedOperationException.class);
    }
}
