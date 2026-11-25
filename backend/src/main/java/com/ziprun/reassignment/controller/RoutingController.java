package com.ziprun.reassignment.controller;

import com.ziprun.reassignment.dto.Requests;
import com.ziprun.reassignment.routing.RoutingStrategySelector;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

@RestController
@RequestMapping("/routing/strategy")
public class RoutingController {

    public record StrategyView(String active, Set<String> available) {
    }

    private final RoutingStrategySelector selector;

    public RoutingController(RoutingStrategySelector selector) {
        this.selector = selector;
    }

    @GetMapping
    public StrategyView get() {
        return new StrategyView(selector.activeName(), selector.available());
    }

    /** Switch strategy at runtime, no restart. Unknown names -> 400. */
    @PatchMapping
    public StrategyView select(@Valid @RequestBody Requests.SelectStrategy body) {
        selector.select(body.strategy());
        return get();
    }
}
