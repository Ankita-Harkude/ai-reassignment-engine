package com.ziprun.reassignment.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "agents")
public class Agent {

    @Id
    private String id;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AgentStatus status;

    @Column(name = "active_order_count", nullable = false)
    private int activeOrderCount;

    // Sprint 2 placeholders - nullable, unused by sprint-1 logic. Activating them needs no migration.
    @Column(name = "current_zone")
    private String currentZone;

    @Column(name = "max_capacity")
    private Integer maxCapacity;

    @Version
    private Long version;

    protected Agent() {
    }

    public Agent(String id, String name, AgentStatus status, int activeOrderCount) {
        this.id = id;
        this.name = name;
        this.status = status;
        this.activeOrderCount = activeOrderCount;
    }

    /** Called when an order is handed to this agent. */
    public void takeOrder() {
        activeOrderCount++;
        if (status == AgentStatus.AVAILABLE) {
            status = AgentStatus.BUSY;
        }
    }

    /** Called when an order leaves this agent (reassigned away). */
    public void releaseOrder() {
        if (activeOrderCount > 0) {
            activeOrderCount--;
        }
        if (activeOrderCount == 0 && status == AgentStatus.BUSY) {
            status = AgentStatus.AVAILABLE;
        }
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public AgentStatus getStatus() { return status; }
    public void setStatus(AgentStatus status) { this.status = status; }
    public int getActiveOrderCount() { return activeOrderCount; }
    public String getCurrentZone() { return currentZone; }
    public Integer getMaxCapacity() { return maxCapacity; }
}
