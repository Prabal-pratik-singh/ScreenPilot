package com.screenpilot.signage.repo;

import com.screenpilot.signage.domain.Screen;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link Screen}.
 */
public interface ScreenRepository extends JpaRepository<Screen, UUID> {

    /** Fetches the screen owning a device token — how player requests are authenticated. */
    Optional<Screen> findByDeviceToken(String deviceToken);

    /** Fetches screens still marked ONLINE whose last heartbeat is older than the cutoff — candidates to flip OFFLINE. */
    @Query("select s from Screen s where s.status = 'ONLINE' and s.lastHeartbeatAt < :cutoff")
    List<Screen> findOnlineWithHeartbeatBefore(@Param("cutoff") Instant cutoff);

    /** Fetches all screens in the given groups — used to scope data for group-restricted users. */
    List<Screen> findByGroupIdIn(List<UUID> groupIds);
}
