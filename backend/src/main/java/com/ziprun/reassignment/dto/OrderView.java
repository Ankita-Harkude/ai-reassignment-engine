package com.ziprun.reassignment.dto;

import com.ziprun.reassignment.domain.Order;
import com.ziprun.reassignment.domain.OrderStatus;

import java.time.Instant;

public record OrderView(String id, String description, OrderStatus status,
                        String assignedAgentId, String assignedAgentName, Instant createdAt) {
    public static OrderView from(Order o) {
        return new OrderView(o.getId(), o.getDescription(), o.getStatus(),
                o.getAssignedAgent().getId(), o.getAssignedAgent().getName(), o.getCreatedAt());
    }
}
