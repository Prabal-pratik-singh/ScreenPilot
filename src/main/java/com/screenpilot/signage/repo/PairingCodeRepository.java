package com.screenpilot.signage.repo;

import com.screenpilot.signage.domain.PairingCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link PairingCode} — the short-lived codes used to
 * link a TV device to a screen.
 */
public interface PairingCodeRepository extends JpaRepository<PairingCode, UUID> {

    /** Fetches the newest row for a code (codes can repeat over time; only the latest one counts). */
    Optional<PairingCode> findFirstByCodeOrderByCreatedAtDesc(String code);

    /** Fetches a code only if it is in the expected status, e.g. a PENDING code an admin is confirming. */
    Optional<PairingCode> findByCodeAndStatus(String code, PairingCode.Status status);

    /** Fetches expired rows still holding a plaintext device token — a cleanup job wipes them. */
    List<PairingCode> findByDeviceTokenPlainIsNotNullAndExpiresAtBefore(Instant cutoff);
}
