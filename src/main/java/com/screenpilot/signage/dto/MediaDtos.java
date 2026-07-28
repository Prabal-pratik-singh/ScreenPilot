package com.screenpilot.signage.dto;

import com.screenpilot.signage.domain.MediaAsset;
import com.screenpilot.signage.security.UrlSigner;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * DTOs (Data Transfer Objects) for the media-library endpoints. Responses include
 * HMAC-signed file/thumbnail URLs so <img>/<video> tags can fetch binaries without an
 * auth header. Each is a Java record; this outer class is only a namespace.
 */
public final class MediaDtos {

    private MediaDtos() {
    }

    /** Sent as a minimal reference to the user who uploaded the asset. */
    public record UploaderRef(UUID id, String name) {
    }

    /** Signed links stay valid this long; clients refetch listings well within it. */
    public static final long MEDIA_URL_TTL_SECONDS = 12 * 3600;

    /** Sent when listing the library or after an upload: asset metadata plus signed download URLs. */
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
            boolean deleted,
            String fileUrl,
            String thumbUrl) {

        public static MediaResponse from(MediaAsset m) {
            UrlSigner signer = UrlSigner.instance();
            String fileUrl = "/api/media/" + m.getId() + "/file?"
                    + signer.signQuery("media:" + m.getId(), MEDIA_URL_TTL_SECONDS);
            String thumbUrl = m.getThumbPath() == null ? null
                    : "/api/media/" + m.getId() + "/thumb?"
                    + signer.signQuery("media:" + m.getId(), MEDIA_URL_TTL_SECONDS);
            return new MediaResponse(
                    m.getId(), m.getName(), m.getType(), m.getMimeType(), m.getSizeBytes(),
                    m.getWidth(), m.getHeight(), m.getDurationSeconds(), m.getFolder(),
                    parseTags(m.getTags()),
                    m.getUploadedBy() == null ? null
                            : new UploaderRef(m.getUploadedBy().getId(), m.getUploadedBy().getFullName()),
                    m.getUploadedAt(), m.getThumbPath() != null, m.isDeleted(), fileUrl, thumbUrl);
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

    /** Received when editing asset metadata (rename, move folder, retag) — never the file itself. */
    public record UpdateMediaRequest(
            @NotBlank @Size(max = 300) String name,
            @Size(max = 200) String folder,
            List<String> tags) {
    }

    /** Sent as a minimal playlist reference inside UsageResponse. */
    public record PlaylistRef(UUID id, String name) {
    }

    /** Sent by the usage endpoint: which playlists reference this asset (warned about before delete). */
    public record UsageResponse(List<PlaylistRef> playlists) {
    }
}
