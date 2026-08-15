package com.vvsgk.reconciliation_engine.controller;
import com.vvsgk.reconciliation_engine.dto.EventRequest;
import com.vvsgk.reconciliation_engine.dto.EventResponse;
import com.vvsgk.reconciliation_engine.service.EventService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/events")
public class EventController {
    private final EventService eventService;
    public EventController(EventService eventService) { this.eventService = eventService; }
    @PostMapping public EventResponse ingest(@Valid @RequestBody EventRequest request) { return eventService.processEvent(request); }
}
