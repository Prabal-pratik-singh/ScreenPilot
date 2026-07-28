package com.screenpilot.signage.controller;

import com.screenpilot.signage.dto.ScheduleDtos;
import com.screenpilot.signage.service.ScheduleService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST CRUD for schedules plus the pre-save conflict check. VIEWER can read;
 * creating, editing, pausing and deleting need CONTENT_MANAGER.
 */
@RestController
@RequestMapping("/api/schedules")
public class ScheduleController {

    private final ScheduleService scheduleService;

    public ScheduleController(ScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    // GET /api/schedules — schedules visible to the user's groups; VIEWER and up
    @GetMapping
    @PreAuthorize("hasRole('VIEWER')")
    public List<ScheduleDtos.ScheduleResponse> list() {
        return scheduleService.list();
    }

    // GET /api/schedules/{id} — one schedule; VIEWER and up
    @GetMapping("/{id}")
    @PreAuthorize("hasRole('VIEWER')")
    public ScheduleDtos.ScheduleResponse get(@PathVariable UUID id) {
        return scheduleService.get(id);
    }

    // POST /api/schedules/preview-conflicts — dry-run overlap check before saving
    // (excludeId skips the schedule being edited); CONTENT_MANAGER and up
    @PostMapping("/preview-conflicts")
    @PreAuthorize("hasRole('CONTENT_MANAGER')")
    public ScheduleDtos.ConflictResponse previewConflicts(@Valid @RequestBody ScheduleDtos.SaveScheduleRequest request,
                                                          @RequestParam(required = false) UUID excludeId) {
        return scheduleService.previewConflicts(request, excludeId);
    }

    // POST /api/schedules — create (may pause overridden schedules); CONTENT_MANAGER and up
    @PostMapping
    @PreAuthorize("hasRole('CONTENT_MANAGER')")
    public ScheduleDtos.ScheduleResponse create(@Valid @RequestBody ScheduleDtos.SaveScheduleRequest request) {
        return scheduleService.create(request);
    }

    // PUT /api/schedules/{id} — edit a schedule; CONTENT_MANAGER and up
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('CONTENT_MANAGER')")
    public ScheduleDtos.ScheduleResponse update(@PathVariable UUID id,
                                                @Valid @RequestBody ScheduleDtos.SaveScheduleRequest request) {
        return scheduleService.update(id, request);
    }

    // POST /api/schedules/{id}/pause — stop it playing without deleting; CONTENT_MANAGER and up
    @PostMapping("/{id}/pause")
    @PreAuthorize("hasRole('CONTENT_MANAGER')")
    public void pause(@PathVariable UUID id) {
        scheduleService.setActive(id, false);
    }

    // POST /api/schedules/{id}/resume — reactivate a paused schedule; CONTENT_MANAGER and up
    @PostMapping("/{id}/resume")
    @PreAuthorize("hasRole('CONTENT_MANAGER')")
    public void resume(@PathVariable UUID id) {
        scheduleService.setActive(id, true);
    }

    // DELETE /api/schedules/{id} — remove a schedule; CONTENT_MANAGER and up
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('CONTENT_MANAGER')")
    public void delete(@PathVariable UUID id) {
        scheduleService.delete(id);
    }
}
