package com.vvsgk.reconciliation_engine.controller;
import com.vvsgk.reconciliation_engine.dto.ReplayRequest;
import com.vvsgk.reconciliation_engine.dto.ReplayResponse;
import com.vvsgk.reconciliation_engine.service.ReplayService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/replay")
public class ReplayController {
    private final ReplayService replayService;
    public ReplayController(ReplayService replayService) { this.replayService = replayService; }
    @PostMapping public ReplayResponse replay(@Valid @RequestBody ReplayRequest request) { return replayService.replay(request); }
}
