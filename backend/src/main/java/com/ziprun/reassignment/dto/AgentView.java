package com.ziprun.reassignment.dto;

import com.ziprun.reassignment.domain.Agent;
import com.ziprun.reassignment.domain.AgentStatus;

public record AgentView(String id, String name, AgentStatus status, int activeOrderCount) {
    public static AgentView from(Agent a) {
        return new AgentView(a.getId(), a.getName(), a.getStatus(), a.getActiveOrderCount());
    }
}
