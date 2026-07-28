package com.screenpilot.signage.web;

import com.screenpilot.signage.dto.UserDtos;
import com.screenpilot.signage.service.UserService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** REST CRUD for portal user accounts. Class-level @PreAuthorize: SUPER_ADMIN only. */
@RestController
@RequestMapping("/api/users")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    // GET /api/users — all accounts
    @GetMapping
    public List<UserDtos.UserResponse> list() {
        return userService.list();
    }

    // POST /api/users — create an account
    @PostMapping
    public UserDtos.UserResponse create(@Valid @RequestBody UserDtos.CreateUserRequest request) {
        return userService.create(request);
    }

    // PUT /api/users/{id} — edit name/role/groups, optionally reset password
    @PutMapping("/{id}")
    public UserDtos.UserResponse update(@PathVariable UUID id, @Valid @RequestBody UserDtos.UpdateUserRequest request) {
        return userService.update(id, request);
    }

    // POST /api/users/{id}/deactivate — disable a login (not your own)
    @PostMapping("/{id}/deactivate")
    public void deactivate(@PathVariable UUID id) {
        userService.setActive(id, false);
    }

    // POST /api/users/{id}/activate — re-enable a login
    @PostMapping("/{id}/activate")
    public void activate(@PathVariable UUID id) {
        userService.setActive(id, true);
    }
}
