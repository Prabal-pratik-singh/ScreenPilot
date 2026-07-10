package com.screenpilot.signage.repo;

import com.screenpilot.signage.domain.Schedule;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ScheduleRepository extends JpaRepository<Schedule, UUID> {

    @EntityGraph(attributePaths = {"screens", "playlist"})
    Optional<Schedule> findWithScreensById(UUID id);

    @EntityGraph(attributePaths = {"screens", "playlist"})
    List<Schedule> findAllByOrderByCreatedAtDesc();

    @Query("select distinct s from Schedule s join fetch s.screens scr left join fetch s.playlist where scr.id = :screenId and s.active = true")
    List<Schedule> findActiveForScreen(@Param("screenId") UUID screenId);

    @Query("select distinct s from Schedule s join s.screens scr where scr.id in :screenIds and s.active = true")
    List<Schedule> findActiveForScreens(@Param("screenIds") List<UUID> screenIds);

    List<Schedule> findByPlaylistIdAndActiveTrue(UUID playlistId);

    List<Schedule> findByLayoutIdAndActiveTrue(UUID layoutId);
}
