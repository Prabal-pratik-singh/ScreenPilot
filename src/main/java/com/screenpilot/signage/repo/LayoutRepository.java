package com.screenpilot.signage.repo;

import com.screenpilot.signage.domain.Layout;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link Layout}. Extending {@code JpaRepository} gives
 * the standard CRUD methods for free; Spring generates the implementation at runtime.
 * The {@code @EntityGraph} annotations force lazy collections to load in the same query,
 * avoiding the N+1 select problem.
 */
public interface LayoutRepository extends JpaRepository<Layout, UUID> {

    /** Fetches one layout with its zones and each zone's playlist loaded eagerly. */
    @EntityGraph(attributePaths = {"zones", "zones.playlist"})
    Optional<Layout> findWithZonesById(UUID id);

    /** Fetches all layouts (zones included), newest edits first — used by the layouts list page. */
    @EntityGraph(attributePaths = {"zones", "zones.playlist"})
    List<Layout> findAllByOrderByUpdatedAtDesc();
}
