package com.screenpilot.signage.dto;

import com.screenpilot.signage.domain.MediaAsset;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public final class MediaDtos {

    private MediaDtos() {
    }

    public record UploaderRef(UUID id, String name) {
    }

    public record MediaResponse(
            UUID id,
            String name,
            MediaAsset.Type type,
            String mimeType,
            long sizeBytes,
            Integer width,
            Integer height,
            Double durationSeconds,
            String folder,
            List<String> tags,
            UploaderRef uploadedBy,
            Instant uploadedAt,
            boolean hasThumb,
            boolean deleted) {

        public static MediaResponse from(MediaAsset m) {
            return new MediaResponse(
                    m.getId(), m.getName(), m.getType(), m.getMimeType(), m.getSizeBytes(),
                    m.getWidth(), m.getHeight(), m.getDurationSeconds(), m.getFolder(),
                    parseTags(m.getTags()),
                    m.getUploadedBy() == null ? null
                            : new UploaderRef(m.getUploadedBy().getId(), m.getUploadedBy().getFullName()),
                    m.getUploadedAt(), m.getThumbPath() != null, m.isDeleted());
        }
    }

    public static List<String> parseTags(String tags) {
        if (tags == null || tags.isBlank()) {
            return List.of();
        }
        return Arrays.stream(tags.split(",")).map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
    }

    public static String joinTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return null;
        }
        return String.join(",", tags.stream().map(String::trim).filter(s -> !s.isEmpty()).distinct().toList());
    }

    public record UpdateMediaRequest(
            @NotBlank @Size(max = 300) String name,
            @Size(max = 200) String folder,
            List<String> tags) {
    }

    public record PlaylistRef(UUID id, String name) {
    }

    public record UsageResponse(List<PlaylistRef> playlists) {
    }
}
