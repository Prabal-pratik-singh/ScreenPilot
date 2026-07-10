package com.screenpilot.signage.repo;

import com.screenpilot.signage.domain.MediaAsset;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface MediaAssetRepository extends JpaRepository<MediaAsset, UUID> {

    List<MediaAsset> findByDeletedFalseOrderByUploadedAtDesc();

    List<MediaAsset> findByIdInAndDeletedFalse(List<UUID> ids);
}
