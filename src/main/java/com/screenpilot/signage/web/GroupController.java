package com.screenpilot.signage.web;

import com.screenpilot.signage.dto.GroupDtos;
import com.screenpilot.signage.service.GroupService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/groups")
public class GroupController {

    private final GroupService groupService;

    public GroupController(GroupService groupService) {
        this.groupService = groupService;
    }

    @GetMapping
    @PreAuthorize("hasRole('VIEWER')")
    public List<GroupDtos.GroupResponse> list() {
        return groupService.list();
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public GroupDtos.GroupResponse create(@Valid @RequestBody GroupDtos.SaveGroupRequest request) {
        return groupService.create(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public GroupDtos.GroupResponse update(@PathVariable UUID id, @Valid @RequestBody GroupDtos.SaveGroupRequest request) {
        return groupService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable UUID id) {
        groupService.delete(id);
    }
}
