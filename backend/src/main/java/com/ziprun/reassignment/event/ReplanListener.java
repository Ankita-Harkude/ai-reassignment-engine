package com.ziprun.reassignment.event;

import com.ziprun.reassignment.service.ReplanService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * The OBSERVE step of the loop. Fires AFTER the status change has committed (so the re-plan sees
 * OFFLINE in the database and never reacts to a rolled-back change) and on a separate thread
 * (so PATCH /agents/{id}/status returns immediately).
 *
 * A failure here cannot reach the HTTP caller; it is logged, and ReplanService already isolates
 * failures per order so one bad order does not abort the rest.
 */
@Component
public class ReplanListener {

    private static final Logger log = LoggerFactory.getLogger(ReplanListener.class);

    private final ReplanService replanService;

    public ReplanListener(ReplanService replanService) {
        this.replanService = replanService;
    }

    @Async("replanExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAgentWentOffline(AgentWentOfflineEvent event) {
        log.info("Agent {} went OFFLINE - starting re-plan", event.agentId());
        try {
            replanService.replanForOfflineAgent(event.agentId());
        } catch (RuntimeException e) {
            log.error("Re-plan for agent {} failed", event.agentId(), e);
        }
    }
}
