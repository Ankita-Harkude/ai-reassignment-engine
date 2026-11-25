package com.ziprun.reassignment.repository;

import com.ziprun.reassignment.domain.ReassignmentSuggestion;
import com.ziprun.reassignment.domain.SuggestionStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface SuggestionRepository extends JpaRepository<ReassignmentSuggestion, Long> {

    @EntityGraph(attributePaths = {"order", "order.assignedAgent", "recommendedAgent"})
    List<ReassignmentSuggestion> findAllByOrderByCreatedAtDesc();

    @EntityGraph(attributePaths = {"order", "order.assignedAgent", "recommendedAgent"})
    List<ReassignmentSuggestion> findByStatusOrderByCreatedAtDesc(SuggestionStatus status);

    @EntityGraph(attributePaths = {"order", "order.assignedAgent", "recommendedAgent"})
    Optional<ReassignmentSuggestion> findWithDetailsById(Long id);

    /** Idempotency lookup: the open (PENDING) suggestion for this trigger + order, if any. */
    @EntityGraph(attributePaths = {"order", "order.assignedAgent", "recommendedAgent"})
    Optional<ReassignmentSuggestion> findByActiveKey(String activeKey);

    @EntityGraph(attributePaths = {"order", "order.assignedAgent", "recommendedAgent"})
    List<ReassignmentSuggestion> findByOrder_IdAndStatus(String orderId, SuggestionStatus status);

    @EntityGraph(attributePaths = {"order", "order.assignedAgent", "recommendedAgent"})
    List<ReassignmentSuggestion> findByStatusAndRecommendedAgent_Id(SuggestionStatus status, String agentId);

    /** [agentId, count] of PENDING suggestions per recommended agent - the "already queued" load. */
    @Query("select s.recommendedAgent.id, count(s) from ReassignmentSuggestion s "
            + "where s.status = com.ziprun.reassignment.domain.SuggestionStatus.PENDING "
            + "group by s.recommendedAgent.id")
    List<Object[]> countPendingByRecommendedAgent();
}
