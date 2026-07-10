package com.screenpilot.signage.repo;

import com.screenpilot.signage.domain.ScreenCommand;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ScreenCommandRepository extends JpaRepository<ScreenCommand, UUID> {

    List<ScreenCommand> findTop10ByScreenIdOrderByCreatedAtDesc(UUID screenId);
}
