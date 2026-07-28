package com.screenpilot.signage.repo;

import com.screenpilot.signage.domain.MediaAsset;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link MediaAsset}. Method names follow Spring Data's
 * naming convention, so the queries are derived automatically — no SQL to write.
 */
public interface MediaAssetRepository extends JpaRepository<MediaAsset, UUID> {

    /** Fetches the visible media library: non-deleted assets, newest uploads first. */
    List<MediaAsset> findByDeletedFalseOrderByUploadedAtDesc();

    /** Fetches only the given ids that are still not soft-deleted (used when validating playlist items). */
    List<MediaAsset> findByIdInAndDeletedFalse(List<UUID> ids);
}
