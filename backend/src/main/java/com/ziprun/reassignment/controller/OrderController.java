package com.ziprun.reassignment.controller;

import com.ziprun.reassignment.domain.OrderStatus;
import com.ziprun.reassignment.dto.OrderView;
import com.ziprun.reassignment.dto.Requests;
import com.ziprun.reassignment.dto.SuggestionView;
import com.ziprun.reassignment.routing.RoutingContext;
import com.ziprun.reassignment.service.OrderService;
import com.ziprun.reassignment.service.SuggestionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderService orders;
    private final SuggestionService suggestions;

    public OrderController(OrderService orders, SuggestionService suggestions) {
        this.orders = orders;
        this.suggestions = suggestions;
    }

    @PostMapping
    public ResponseEntity<OrderView> create(@Valid @RequestBody Requests.CreateOrder body) {
        OrderView created = orders.create(body.description(), body.assignedAgentId());
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @GetMapping
    public List<OrderView> list(@RequestParam(required = false) OrderStatus status) {
        return orders.list(status);
    }

    /** On-demand suggestion using the active strategy. 201 if new, 200 if an open one already existed. */
    @PostMapping("/{id}/suggest")
    public ResponseEntity<SuggestionView> suggest(@PathVariable String id) {
        SuggestionService.Outcome outcome = suggestions.suggest(id, RoutingContext.initial());
        return ResponseEntity.status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(outcome.suggestion());
    }
}
