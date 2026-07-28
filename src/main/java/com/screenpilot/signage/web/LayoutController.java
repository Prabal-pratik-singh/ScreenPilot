package com.screenpilot.signage.web;

import com.screenpilot.signage.dto.LayoutDtos;
import com.screenpilot.signage.service.LayoutService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** REST CRUD for multi-zone layouts: VIEWER can read, CONTENT_MANAGER can change. */
@RestController
@RequestMapping("/api/layouts")
public class LayoutController {

    private final LayoutService layoutService;

    public LayoutController(LayoutService layoutService) {
        this.layoutService = layoutService;
    }

    // GET /api/layouts — all layouts; VIEWER and up
    @GetMapping
    @PreAuthorize("hasRole('VIEWER')")
    public List<LayoutDtos.LayoutResponse> list() {
        return layoutService.list();
    }

    // GET /api/layouts/{id} — one layout with zones; VIEWER and up
    @GetMapping("/{id}")
    @PreAuthorize("hasRole('VIEWER')")
    public LayoutDtos.LayoutResponse get(@PathVariable UUID id) {
        return layoutService.get(id);
    }

    // POST /api/layouts — create from a preset; CONTENT_MANAGER and up
    @PostMapping
    @PreAuthorize("hasRole('CONTENT_MANAGER')")
    public LayoutDtos.LayoutResponse create(@Valid @RequestBody LayoutDtos.CreateLayoutRequest request) {
        return layoutService.create(request);
    }

    // PUT /api/layouts/{id} — save the editor state (replaces zones); CONTENT_MANAGER and up
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('CONTENT_MANAGER')")
    public LayoutDtos.LayoutResponse update(@PathVariable UUID id, @Valid @RequestBody LayoutDtos.SaveLayoutRequest request) {
        return layoutService.update(id, request);
    }

    // DELETE /api/layouts/{id} — delete when no active schedule uses it; CONTENT_MANAGER and up
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('CONTENT_MANAGER')")
    public void delete(@PathVariable UUID id) {
        layoutService.delete(id);
    }
}
