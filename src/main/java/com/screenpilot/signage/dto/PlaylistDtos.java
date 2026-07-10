package com.screenpilot.signage.dto;

import com.screenpilot.signage.domain.Playlist;
import com.screenpilot.signage.domain.PlaylistItem;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class PlaylistDtos {

    private PlaylistDtos() {
    }

    public static final int DEFAULT_STATIC_SECONDS = 10;
    public static final int DEFAULT_EXTERNAL_SECONDS = 20;

    public record ItemResponse(
            UUID id,
            int position,
            PlaylistItem.ItemType itemType,
            MediaDtos.MediaResponse media,
            String url,
            String title,
            Integer durationSeconds,
            double effectiveDurationSeconds) {

        public static ItemResponse from(PlaylistItem item) {
            return new ItemResponse(
                    item.getId(), item.getPosition(), item.getItemType(),
                    item.getMedia() == null ? null : MediaDtos.MediaResponse.from(item.getMedia()),
                    item.getUrl(), item.getTitle(), item.getDurationSeconds(),
                    effectiveDuration(item));
        }
    }

    /** Videos always play full length; other items use their configured duration. */
    public static double effectiveDuration(PlaylistItem item) {
        if (item.getItemType() == PlaylistItem.ItemType.MEDIA && item.getMedia() != null) {
            if (item.getMedia().getType() == com.screenpilot.signage.domain.MediaAsset.Type.VIDEO) {
                return item.getMedia().getDurationSeconds() != null ? item.getMedia().getDurationSeconds() : 30;
            }
            return item.getDurationSeconds() != null ? item.getDurationSeconds() : DEFAULT_STATIC_SECONDS;
        }
        return item.getDurationSeconds() != null ? item.getDurationSeconds() : DEFAULT_EXTERNAL_SECONDS;
    }

    public record PlaylistResponse(
            UUID id,
            String name,
            String description,
            int itemCount,
            double totalDurationSeconds,
            String createdByName,
            Instant createdAt,
            Instant updatedAt,
            List<ItemResponse> items) {

        public static PlaylistResponse from(Playlist p, boolean includeItems) {
            List<ItemResponse> items = includeItems
                    ? p.getItems().stream().map(ItemResponse::from).toList()
                    : null;
            double total = p.getItems().stream()
                    .filter(i -> i.getMedia() == null || !i.getMedia().isDeleted())
                    .mapToDouble(PlaylistDtos::effectiveDuration).sum();
            return new PlaylistResponse(
                    p.getId(), p.getName(), p.getDescription(), p.getItems().size(), total,
                    p.getCreatedBy() == null ? null : p.getCreatedBy().getFullName(),
                    p.getCreatedAt(), p.getUpdatedAt(), items);
        }
    }

    public record SavePlaylistRequest(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 500) String description) {
    }

    public record SaveItemRequest(
            @NotNull PlaylistItem.ItemType itemType,
            UUID mediaId,
            @Size(max = 2000) String url,
            @Size(max = 300) String title,
            Integer durationSeconds) {
    }

    public record SaveItemsRequest(@NotNull List<SaveItemRequest> items) {
    }
}
