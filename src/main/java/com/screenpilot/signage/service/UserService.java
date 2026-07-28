package com.screenpilot.signage.service;

import com.screenpilot.signage.domain.ScreenGroup;
import com.screenpilot.signage.domain.User;
import com.screenpilot.signage.dto.UserDtos;
import com.screenpilot.signage.error.ApiException;
import com.screenpilot.signage.repo.ScreenGroupRepository;
import com.screenpilot.signage.repo.UserRepository;
import com.screenpilot.signage.security.CurrentUser;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/**
 * Admin management of portal users: create, edit, activate/deactivate, and
 * assign screen-group restrictions. Passwords are stored only as
 * PasswordEncoder (BCrypt) hashes; you can never deactivate yourself.
 */
@Service
public class UserService {

    private final UserRepository userRepository;
    private final ScreenGroupRepository groupRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, ScreenGroupRepository groupRepository,
                       PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.groupRepository = groupRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /** All users, oldest first. */
    @Transactional(readOnly = true)
    public List<UserDtos.UserResponse> list() {
        return userRepository.findAll().stream()
                .sorted(Comparator.comparing(User::getCreatedAt))
                .map(UserDtos.UserResponse::from)
                .toList();
    }

    /** Creates a user with a hashed password; 409 when the email is already registered. */
    @Transactional
    public UserDtos.UserResponse create(UserDtos.CreateUserRequest req) {
        String email = req.email().trim().toLowerCase();
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw ApiException.conflict("A user with this email already exists");
        }
        User user = new User(email, passwordEncoder.encode(req.password()), req.fullName().trim(), req.role());
        applyGroups(user, req.groupIds());
        return UserDtos.UserResponse.from(userRepository.save(user));
    }

    /** Updates name/role, optionally resets the password, and toggles active status. */
    @Transactional
    public UserDtos.UserResponse update(UUID id, UserDtos.UpdateUserRequest req) {
        User user = userRepository.findById(id).orElseThrow(() -> ApiException.notFound("User not found"));
        user.setFullName(req.fullName().trim());
        user.setRole(req.role());
        // blank password means "leave the current password alone"
        if (req.password() != null && !req.password().isBlank()) {
            user.setPasswordHash(passwordEncoder.encode(req.password()));
        }
        if (req.active() != null) {
            if (!req.active() && user.getId().equals(CurrentUser.get().id())) {
                throw ApiException.badRequest("You cannot deactivate your own account");
            }
            user.setActive(req.active());
        }
        applyGroups(user, req.groupIds());
        return UserDtos.UserResponse.from(userRepository.save(user));
    }

    /** Enables or disables a login; self-deactivation is blocked. */
    @Transactional
    public void setActive(UUID id, boolean active) {
        User user = userRepository.findById(id).orElseThrow(() -> ApiException.notFound("User not found"));
        if (!active && user.getId().equals(CurrentUser.get().id())) {
            throw ApiException.badRequest("You cannot deactivate your own account");
        }
        user.setActive(active);
        userRepository.save(user);
    }

    // replaces the user's group restrictions; null means "leave unchanged"
    private void applyGroups(User user, List<UUID> groupIds) {
        if (groupIds == null) {
            return;
        }
        List<ScreenGroup> groups = groupRepository.findAllById(groupIds);
        if (groups.size() != new HashSet<>(groupIds).size()) {
            throw ApiException.badRequest("One or more screen groups do not exist");
        }
        user.setGroups(new HashSet<>(groups));
    }
}
