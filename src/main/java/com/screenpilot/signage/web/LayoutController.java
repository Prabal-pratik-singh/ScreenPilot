package com.screenpilot.signage.web;

import com.screenpilot.signage.dto.LayoutDtos;
import com.screenpilot.signage.service.LayoutService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/layouts")
public class LayoutController {

    private final LayoutService layoutService;

    public LayoutController(LayoutService layoutService) {
        this.layoutService = layoutService;
    }

    @GetMapping
    @PreAuthorize("hasRole('VIEWER')")
    public List<LayoutDtos.LayoutResponse> list() {
        return layoutService.list();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('VIEWER')")
    public LayoutDtos.LayoutResponse get(@PathVariable UUID id) {
        return layoutService.get(id);
    }

    @PostMapping
    @PreAuthorize("hasRole('CONTENT_MANAGER')")
    public LayoutDtos.LayoutResponse create(@Valid @RequestBody LayoutDtos.CreateLayoutRequest request) {
        return layoutService.create(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('CONTENT_MANAGER')")
    public LayoutDtos.LayoutResponse update(@PathVariable UUID id, @Valid @RequestBody LayoutDtos.SaveLayoutRequest request) {
        return layoutService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('CONTENT_MANAGER')")
    public void delete(@PathVariable UUID id) {
        layoutService.delete(id);
    }
}
