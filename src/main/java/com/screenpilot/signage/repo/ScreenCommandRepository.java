package com.screenpilot.signage.repo;

import com.screenpilot.signage.domain.ScreenCommand;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link ScreenCommand} — the remote commands sent to
 * player devices.
 */
public interface ScreenCommandRepository extends JpaRepository<ScreenCommand, UUID> {

    /** Fetches the 10 most recent commands for a screen — the command history shown on its detail page. */
    List<ScreenCommand> findTop10ByScreenIdOrderByCreatedAtDesc(UUID screenId);
}
