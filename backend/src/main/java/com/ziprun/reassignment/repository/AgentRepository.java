package com.ziprun.reassignment.repository;

import com.ziprun.reassignment.domain.Agent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentRepository extends JpaRepository<Agent, String> {
    List<Agent> findAllByOrderByIdAsc();
}
