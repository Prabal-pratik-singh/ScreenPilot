package com.screenpilot.signage.repo;

import com.screenpilot.signage.domain.PlaybackLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link PlaybackLog} — the proof-of-play rows that
 * feed reports and CSV/PDF exports.
 */
public interface PlaybackLogRepository extends JpaRepository<PlaybackLog, Long> {

    /** Fetches logs whose start time falls in [from, to); a null screenIds list means all screens. */
    @Query("select l from PlaybackLog l where l.startedAt >= :from and l.startedAt < :to " +
            "and (:screenIds is null or l.screenId in :screenIds)")
    List<PlaybackLog> findInRange(@Param("from") Instant from, @Param("to") Instant to,
                                  @Param("screenIds") List<UUID> screenIds);
}
