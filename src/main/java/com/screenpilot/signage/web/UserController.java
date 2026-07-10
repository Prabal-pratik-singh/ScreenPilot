package com.screenpilot.signage.web;

import com.screenpilot.signage.dto.UserDtos;
import com.screenpilot.signage.service.UserService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/users")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public List<UserDtos.UserResponse> list() {
        return userService.list();
    }

    @PostMapping
    public UserDtos.UserResponse create(@Valid @RequestBody UserDtos.CreateUserRequest request) {
        return userService.create(request);
    }

    @PutMapping("/{id}")
    public UserDtos.UserResponse update(@PathVariable UUID id, @Valid @RequestBody UserDtos.UpdateUserRequest request) {
        return userService.update(id, request);
    }

    @PostMapping("/{id}/deactivate")
    public void deactivate(@PathVariable UUID id) {
        userService.setActive(id, false);
    }

    @PostMapping("/{id}/activate")
    public void activate(@PathVariable UUID id) {
        userService.setActive(id, true);
    }
}
