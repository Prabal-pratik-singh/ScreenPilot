package com.screenpilot.signage.repo;

import com.screenpilot.signage.domain.ScreenGroup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ScreenGroupRepository extends JpaRepository<ScreenGroup, UUID> {

    Optional<ScreenGroup> findByNameIgnoreCase(String name);
}
