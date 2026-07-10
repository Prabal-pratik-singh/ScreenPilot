package com.screenpilot.signage.repo;

import com.screenpilot.signage.domain.Layout;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LayoutRepository extends JpaRepository<Layout, UUID> {

    @EntityGraph(attributePaths = {"zones", "zones.playlist"})
    Optional<Layout> findWithZonesById(UUID id);

    @EntityGraph(attributePaths = {"zones", "zones.playlist"})
    List<Layout> findAllByOrderByUpdatedAtDesc();
}
