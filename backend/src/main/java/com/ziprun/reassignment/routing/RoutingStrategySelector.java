package com.ziprun.reassignment.routing;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Registry + runtime switch. Spring injects every RoutingStrategy bean keyed by bean name, so a new
 * strategy needs no change here. The active name lives in an AtomicReference: switching is thread-safe
 * and takes effect on the next call from either caller (HTTP or async re-plan), with no restart.
 */
@Component
public class RoutingStrategySelector {

    private static final Logger log = LoggerFactory.getLogger(RoutingStrategySelector.class);

    private final Map<String, RoutingStrategy> strategies;
    private final AtomicReference<String> active;

    public RoutingStrategySelector(Map<String, RoutingStrategy> strategies,
                                   @Value("${routing.strategy:rule-based}") String initial) {
        this.strategies = Map.copyOf(strategies);
        this.active = new AtomicReference<>(initial);
    }

    /** Fail fast at startup if routing.strategy names a strategy that doesn't exist. */
    @PostConstruct
    void validateConfiguredStrategy() {
        if (!strategies.containsKey(active.get())) {
            throw new IllegalStateException("routing.strategy='" + active.get()
                    + "' is not a registered strategy. Available: " + available());
        }
        log.info("Routing strategy: {} (available: {})", active.get(), available());
    }

    public RoutingStrategy active() {
        return strategies.get(active.get());
    }

    public String activeName() {
        return active.get();
    }

    public TreeSet<String> available() {
        return new TreeSet<>(strategies.keySet());
    }

    public void select(String name) {
        if (name == null || !strategies.containsKey(name)) {
            throw new IllegalArgumentException("Unknown routing strategy '" + name + "'. Available: " + available());
        }
        String previous = active.getAndSet(name);
        log.info("Routing strategy switched: {} -> {}", previous, name);
    }
}
