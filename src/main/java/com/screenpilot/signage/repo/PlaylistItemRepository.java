package com.screenpilot.signage.repo;

import com.screenpilot.signage.domain.PlaylistItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PlaylistItemRepository extends JpaRepository<PlaylistItem, UUID> {

    List<PlaylistItem> findByMediaId(UUID mediaId);
}
