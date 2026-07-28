package com.screenpilot.signage.web;

import com.screenpilot.signage.dto.GroupDtos;
import com.screenpilot.signage.service.GroupService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** REST CRUD for screen groups: viewing is open to all roles, changes need ADMIN. */
@RestController
@RequestMapping("/api/groups")
public class GroupController {

    private final GroupService groupService;

    public GroupController(GroupService groupService) {
        this.groupService = groupService;
    }

    // GET /api/groups — all groups with screen counts; VIEWER and up
    @GetMapping
    @PreAuthorize("hasRole('VIEWER')")
    public List<GroupDtos.GroupResponse> list() {
        return groupService.list();
    }

    // POST /api/groups — create a group; ADMIN only
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public GroupDtos.GroupResponse create(@Valid @RequestBody GroupDtos.SaveGroupRequest request) {
        return groupService.create(request);
    }

    // PUT /api/groups/{id} — rename/edit a group; ADMIN only
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public GroupDtos.GroupResponse update(@PathVariable UUID id, @Valid @RequestBody GroupDtos.SaveGroupRequest request) {
        return groupService.update(id, request);
    }

    // DELETE /api/groups/{id} — delete an empty group; ADMIN only
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable UUID id) {
        groupService.delete(id);
    }
}
