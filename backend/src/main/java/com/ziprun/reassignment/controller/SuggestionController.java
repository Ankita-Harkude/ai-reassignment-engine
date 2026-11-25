package com.ziprun.reassignment.controller;

import com.ziprun.reassignment.domain.SuggestionStatus;
import com.ziprun.reassignment.dto.Requests;
import com.ziprun.reassignment.dto.SuggestionView;
import com.ziprun.reassignment.service.SuggestionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/suggestions")
public class SuggestionController {

    private final SuggestionService service;

    public SuggestionController(SuggestionService service) {
        this.service = service;
    }

    @GetMapping
    public List<SuggestionView> list(@RequestParam(required = false) SuggestionStatus status) {
        return service.list(status);
    }

    /** Ops decision: body {"status":"ACCEPTED"} or {"status":"REJECTED"}. */
    @PatchMapping("/{id}")
    public SuggestionView resolve(@PathVariable Long id, @Valid @RequestBody Requests.UpdateSuggestion body) {
        return service.resolve(id, body.status());
    }
}
