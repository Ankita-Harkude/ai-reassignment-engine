package com.ziprun.reassignment.service;

import com.ziprun.reassignment.domain.Agent;
import com.ziprun.reassignment.domain.AgentStatus;
import com.ziprun.reassignment.dto.AgentView;
import com.ziprun.reassignment.event.AgentWentOfflineEvent;
import com.ziprun.reassignment.exception.NotFoundException;
import com.ziprun.reassignment.repository.AgentRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class AgentService {

    private final AgentRepository agents;
    private final ApplicationEventPublisher events;

    public AgentService(AgentRepository agents, ApplicationEventPublisher events) {
        this.agents = agents;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public List<AgentView> list() {
        return agents.findAllByOrderByIdAsc().stream().map(AgentView::from).toList();
    }

    /**
     * Changes availability. Does the minimum on the request path: update the row and publish an event.
     * The re-plan itself runs after commit on another thread. The event fires only on a genuine
     * transition INTO offline, so repeating "OFFLINE" is a harmless no-op.
     */
    @Transactional
    public AgentView updateStatus(String agentId, AgentStatus newStatus) {
        Agent agent = agents.findById(agentId).orElseThrow(() -> new NotFoundException("Agent", agentId));
        AgentStatus previous = agent.getStatus();
        if (previous != newStatus) {
            agent.setStatus(newStatus);
            if (newStatus == AgentStatus.OFFLINE) {
                events.publishEvent(new AgentWentOfflineEvent(agent.getId(), agent.getName(), Instant.now()));
            }
        }
        return AgentView.from(agent);
    }
}
