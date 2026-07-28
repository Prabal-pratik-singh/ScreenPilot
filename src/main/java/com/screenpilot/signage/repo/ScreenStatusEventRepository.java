package com.screenpilot.signage.repo;

import com.screenpilot.signage.domain.ScreenStatusEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link ScreenStatusEvent} — the online/offline history
 * rows behind uptime reporting.
 */
public interface ScreenStatusEventRepository extends JpaRepository<ScreenStatusEvent, Long> {

    /** Fetches status flips for the given screens up to a point in time, oldest first — replayed to compute uptime. */
    @Query("select e from ScreenStatusEvent e where e.screenId in :screenIds and e.at < :before order by e.at asc")
    List<ScreenStatusEvent> findBefore(@Param("screenIds") List<UUID> screenIds, @Param("before") Instant before);
}
