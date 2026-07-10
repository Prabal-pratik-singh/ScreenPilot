package com.screenpilot.signage.web;

import com.screenpilot.signage.dto.ScheduleDtos;
import com.screenpilot.signage.service.ScheduleService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/schedules")
public class ScheduleController {

    private final ScheduleService scheduleService;

    public ScheduleController(ScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    @GetMapping
    @PreAuthorize("hasRole('VIEWER')")
    public List<ScheduleDtos.ScheduleResponse> list() {
        return scheduleService.list();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('VIEWER')")
    public ScheduleDtos.ScheduleResponse get(@PathVariable UUID id) {
        return scheduleService.get(id);
    }

    @PostMapping("/preview-conflicts")
    @PreAuthorize("hasRole('CONTENT_MANAGER')")
    public ScheduleDtos.ConflictResponse previewConflicts(@Valid @RequestBody ScheduleDtos.SaveScheduleRequest request,
                                                          @RequestParam(required = false) UUID excludeId) {
        return scheduleService.previewConflicts(request, excludeId);
    }

    @PostMapping
    @PreAuthorize("hasRole('CONTENT_MANAGER')")
    public ScheduleDtos.ScheduleResponse create(@Valid @RequestBody ScheduleDtos.SaveScheduleRequest request) {
        return scheduleService.create(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('CONTENT_MANAGER')")
    public ScheduleDtos.ScheduleResponse update(@PathVariable UUID id,
                                                @Valid @RequestBody ScheduleDtos.SaveScheduleRequest request) {
        return scheduleService.update(id, request);
    }

    @PostMapping("/{id}/pause")
    @PreAuthorize("hasRole('CONTENT_MANAGER')")
    public void pause(@PathVariable UUID id) {
        scheduleService.setActive(id, false);
    }

    @PostMapping("/{id}/resume")
    @PreAuthorize("hasRole('CONTENT_MANAGER')")
    public void resume(@PathVariable UUID id) {
        scheduleService.setActive(id, true);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('CONTENT_MANAGER')")
    public void delete(@PathVariable UUID id) {
        scheduleService.delete(id);
    }
}
