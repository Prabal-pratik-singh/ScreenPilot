package com.screenpilot.signage.repo;

import com.screenpilot.signage.domain.Schedule;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link Schedule}. Combines derived query methods,
 * {@code @EntityGraph} eager loading and hand-written JPQL {@code @Query} lookups.
 */
public interface ScheduleRepository extends JpaRepository<Schedule, UUID> {

    /** Fetches one schedule with its target screens and playlist loaded eagerly. */
    @EntityGraph(attributePaths = {"screens", "playlist"})
    Optional<Schedule> findWithScreensById(UUID id);

    /** Fetches all schedules (targets + playlist included), newest first — the schedules list page. */
    @EntityGraph(attributePaths = {"screens", "playlist"})
    List<Schedule> findAllByOrderByCreatedAtDesc();

    /** Fetches the active schedules targeting one screen — used to build that player's content plan. */
    @Query("select distinct s from Schedule s join fetch s.screens scr left join fetch s.playlist where scr.id = :screenId and s.active = true")
    List<Schedule> findActiveForScreen(@Param("screenId") UUID screenId);

    /** Fetches active schedules touching any of the given screens — used for conflict detection. */
    @Query("select distinct s from Schedule s join s.screens scr where scr.id in :screenIds and s.active = true")
    List<Schedule> findActiveForScreens(@Param("screenIds") List<UUID> screenIds);

    /** Fetches active schedules that play the given playlist (checked before deleting a playlist). */
    List<Schedule> findByPlaylistIdAndActiveTrue(UUID playlistId);

    /** Fetches active schedules that play the given layout (checked before deleting a layout). */
    List<Schedule> findByLayoutIdAndActiveTrue(UUID layoutId);
}
