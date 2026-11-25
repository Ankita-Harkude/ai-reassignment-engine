package com.ziprun.reassignment.domain;

import com.ziprun.reassignment.exception.InvalidTransitionException;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "orders")
public class Order {

    @Id
    private String id;

    @Column(nullable = false)
    private String description;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assigned_agent_id")
    private Agent assignedAgent;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    // Sprint 2 / 3 placeholders - nullable, unused today.
    @Column(name = "pickup_zone")
    private String pickupZone;

    @Column(name = "dropoff_zone")
    private String dropoffZone;

    @Column(name = "weight_class")
    private String weightClass;

    @Column(name = "sla_deadline")
    private Instant slaDeadline;

    @Version
    private Long version;

    protected Order() {
    }

    public Order(String id, String description, Agent assignedAgent) {
        this.id = id;
        this.description = description;
        this.assignedAgent = assignedAgent;
        this.status = OrderStatus.ASSIGNED;
        this.createdAt = Instant.now();
    }

    // ---- state machine -------------------------------------------------------------------

    /** ASSIGNED | REASSIGNED -> REASSIGNMENT_PENDING (idempotent if already pending). */
    public void markReassignmentPending() {
        if (status == OrderStatus.REASSIGNMENT_PENDING) {
            return;
        }
        if (status == OrderStatus.DELIVERED) {
            throw new InvalidTransitionException("Order " + id + " is already DELIVERED");
        }
        status = OrderStatus.REASSIGNMENT_PENDING;
    }

    /** REASSIGNMENT_PENDING -> REASSIGNED, handing the order to the new agent. */
    public void reassignTo(Agent newAgent) {
        if (status != OrderStatus.REASSIGNMENT_PENDING) {
            throw new InvalidTransitionException(
                    "Order " + id + " is " + status + ", expected REASSIGNMENT_PENDING");
        }
        this.assignedAgent = newAgent;
        this.status = OrderStatus.REASSIGNED;
    }

    public String getId() { return id; }
    public String getDescription() { return description; }
    public Agent getAssignedAgent() { return assignedAgent; }
    public OrderStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public String getPickupZone() { return pickupZone; }
    public String getDropoffZone() { return dropoffZone; }
    public String getWeightClass() { return weightClass; }
    public Instant getSlaDeadline() { return slaDeadline; }
}
