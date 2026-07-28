package com.screenpilot.signage.repo;

import com.screenpilot.signage.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link User}.
 */
public interface UserRepository extends JpaRepository<User, UUID> {

    /** Fetches a user by email, case-insensitively — the lookup behind login. */
    Optional<User> findByEmailIgnoreCase(String email);

    /** Checks whether an email is already taken before creating a new account. */
    boolean existsByEmailIgnoreCase(String email);
}
