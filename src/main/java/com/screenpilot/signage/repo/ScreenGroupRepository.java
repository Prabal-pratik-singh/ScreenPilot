package com.screenpilot.signage.repo;

import com.screenpilot.signage.domain.ScreenGroup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link ScreenGroup}.
 */
public interface ScreenGroupRepository extends JpaRepository<ScreenGroup, UUID> {

    /** Fetches a group by name, case-insensitively — used to block duplicate group names. */
    Optional<ScreenGroup> findByNameIgnoreCase(String name);
}
