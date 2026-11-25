package com.ziprun.reassignment.repository;

import com.ziprun.reassignment.domain.Order;
import com.ziprun.reassignment.domain.OrderStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, String> {

    // The entity graph fetches the agent in the same query - no N+1 when mapping lists to DTOs.
    @EntityGraph(attributePaths = "assignedAgent")
    List<Order> findAllByOrderByIdAsc();

    @EntityGraph(attributePaths = "assignedAgent")
    List<Order> findByStatusOrderByIdAsc(OrderStatus status);

    @EntityGraph(attributePaths = "assignedAgent")
    Optional<Order> findWithAgentById(String id);

    @EntityGraph(attributePaths = "assignedAgent")
    List<Order> findByAssignedAgent_IdAndStatusInOrderByIdAsc(String agentId, Collection<OrderStatus> statuses);
}
