package com.ziprun.reassignment.routing;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoutingStrategySelectorTest {

    private final RoutingStrategy a = req -> List.of();
    private final RoutingStrategy b = req -> List.of();
    private final Map<String, RoutingStrategy> registry = Map.of("rule-based", a, "ai", b);

    @Test
    void switchesAtRuntimeWithoutRestart() {
        var selector = new RoutingStrategySelector(registry, "rule-based");
        selector.validateConfiguredStrategy();
        assertThat(selector.active()).isSameAs(a);

        selector.select("ai");

        assertThat(selector.active()).isSameAs(b);
        assertThat(selector.activeName()).isEqualTo("ai");
    }

    @Test
    void newStrategyJustNeedsToBeRegistered() {
        RoutingStrategy zone = req -> List.of();
        var selector = new RoutingStrategySelector(
                Map.of("rule-based", a, "ai", b, "zone-affinity", zone), "rule-based");

        selector.select("zone-affinity");

        assertThat(selector.active()).isSameAs(zone);
    }

    @Test
    void unknownStrategyIsRejectedAndActiveIsUnchanged() {
        var selector = new RoutingStrategySelector(registry, "rule-based");

        assertThatThrownBy(() -> selector.select("nope")).isInstanceOf(IllegalArgumentException.class);
        assertThat(selector.activeName()).isEqualTo("rule-based");
    }

    @Test
    void misconfiguredStartupStrategyFailsFast() {
        var selector = new RoutingStrategySelector(registry, "typo");

        assertThatThrownBy(selector::validateConfiguredStrategy).isInstanceOf(IllegalStateException.class);
    }
}
