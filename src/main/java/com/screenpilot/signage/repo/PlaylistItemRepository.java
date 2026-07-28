package com.screenpilot.signage.repo;

import com.screenpilot.signage.domain.PlaylistItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link PlaylistItem}. Items are normally managed
 * through their parent playlist; this exists for cross-playlist lookups.
 */
public interface PlaylistItemRepository extends JpaRepository<PlaylistItem, UUID> {

    /** Fetches every playlist item that uses the given media asset — powers the "where is this used?" check. */
    List<PlaylistItem> findByMediaId(UUID mediaId);
}
