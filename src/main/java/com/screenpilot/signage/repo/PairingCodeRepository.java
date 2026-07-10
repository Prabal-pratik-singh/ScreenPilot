package com.screenpilot.signage.repo;

import com.screenpilot.signage.domain.PairingCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PairingCodeRepository extends JpaRepository<PairingCode, UUID> {

    Optional<PairingCode> findFirstByCodeOrderByCreatedAtDesc(String code);

    Optional<PairingCode> findByCodeAndStatus(String code, PairingCode.Status status);
}
