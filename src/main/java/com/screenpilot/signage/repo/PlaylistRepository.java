package com.screenpilot.signage.repo;

import com.screenpilot.signage.domain.Playlist;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link Playlist}. {@code @EntityGraph} pulls the lazy
 * items (and their media) in the same query to avoid N+1 selects.
 */
public interface PlaylistRepository extends JpaRepository<Playlist, UUID> {

    /** Fetches one playlist with its items and each item's media loaded eagerly. */
    @EntityGraph(attributePaths = {"items", "items.media"})
    Optional<Playlist> findWithItemsById(UUID id);

    /** Fetches all playlists (items included), most recently edited first. */
    @EntityGraph(attributePaths = {"items", "items.media"})
    List<Playlist> findAllByOrderByUpdatedAtDesc();
}
