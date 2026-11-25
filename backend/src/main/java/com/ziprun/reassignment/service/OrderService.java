package com.ziprun.reassignment.service;

import com.ziprun.reassignment.domain.Agent;
import com.ziprun.reassignment.domain.AgentStatus;
import com.ziprun.reassignment.domain.Order;
import com.ziprun.reassignment.domain.OrderStatus;
import com.ziprun.reassignment.dto.OrderView;
import com.ziprun.reassignment.exception.InvalidTransitionException;
import com.ziprun.reassignment.exception.NotFoundException;
import com.ziprun.reassignment.repository.AgentRepository;
import com.ziprun.reassignment.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class OrderService {

    private final OrderRepository orders;
    private final AgentRepository agents;

    public OrderService(OrderRepository orders, AgentRepository agents) {
        this.orders = orders;
        this.agents = agents;
    }

    /** Simulates the morning manual assignment: creates an order already held by an agent. */
    @Transactional
    public OrderView create(String description, String agentId) {
        Agent agent = agents.findById(agentId).orElseThrow(() -> new NotFoundException("Agent", agentId));
        if (agent.getStatus() == AgentStatus.OFFLINE) {
            throw new InvalidTransitionException("Agent " + agentId + " is OFFLINE and cannot take new orders");
        }
        Order order = new Order(nextId(), description.trim(), agent);
        agent.takeOrder();
        return OrderView.from(orders.save(order));
    }

    @Transactional(readOnly = true)
    public List<OrderView> list(OrderStatus status) {
        List<Order> found = status == null
                ? orders.findAllByOrderByIdAsc()
                : orders.findByStatusOrderByIdAsc(status);
        return found.stream().map(OrderView::from).toList();
    }

    /** Flags an affected order as awaiting reassignment (idempotent). Used when no agent can take it yet. */
    @Transactional
    public void markReassignmentPending(String orderId) {
        orders.findById(orderId).ifPresent(Order::markReassignmentPending);
    }

    private String nextId() {
        long n = orders.count() + 1;
        String id;
        do {
            id = String.format("ORD-%03d", n++);
        } while (orders.existsById(id));
        return id;
    }
}
