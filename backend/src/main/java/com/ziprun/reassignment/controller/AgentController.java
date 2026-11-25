package com.ziprun.reassignment.controller;

import com.ziprun.reassignment.dto.AgentView;
import com.ziprun.reassignment.dto.Requests;
import com.ziprun.reassignment.service.AgentService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/agents")
public class AgentController {

    private final AgentService service;

    public AgentController(AgentService service) {
        this.service = service;
    }

    @GetMapping
    public List<AgentView> list() {
        return service.list();
    }

    /** Returns immediately; if the new status is OFFLINE the re-plan runs asynchronously afterwards. */
    @PatchMapping("/{id}/status")
    public AgentView updateStatus(@PathVariable String id, @Valid @RequestBody Requests.UpdateAgentStatus body) {
        return service.updateStatus(id, body.status());
    }
}
