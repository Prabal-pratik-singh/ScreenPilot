package com.screenpilot.signage.repo;

import com.screenpilot.signage.domain.Playlist;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PlaylistRepository extends JpaRepository<Playlist, UUID> {

    @EntityGraph(attributePaths = {"items", "items.media"})
    Optional<Playlist> findWithItemsById(UUID id);

    @EntityGraph(attributePaths = {"items", "items.media"})
    List<Playlist> findAllByOrderByUpdatedAtDesc();
}
