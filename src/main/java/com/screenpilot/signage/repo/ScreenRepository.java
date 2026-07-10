package com.screenpilot.signage.repo;

import com.screenpilot.signage.domain.Screen;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ScreenRepository extends JpaRepository<Screen, UUID> {

    Optional<Screen> findByDeviceToken(String deviceToken);

    @Query("select s from Screen s where s.status = 'ONLINE' and s.lastHeartbeatAt < :cutoff")
    List<Screen> findOnlineWithHeartbeatBefore(@Param("cutoff") Instant cutoff);

    List<Screen> findByGroupIdIn(List<UUID> groupIds);
}
