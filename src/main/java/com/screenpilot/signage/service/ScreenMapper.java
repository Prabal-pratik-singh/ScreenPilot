package com.screenpilot.signage.service;

import com.screenpilot.signage.domain.Screen;
import com.screenpilot.signage.dto.ScreenDtos;
import com.screenpilot.signage.dto.UserDtos;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Converts a Screen entity into its API DTO: computes how long the screen has
 * been offline, parses the stored media-state JSON, and attaches an
 * HMAC-signed thumbnail URL for the item currently on air.
 */
@Component
public class ScreenMapper {

    private final ObjectMapper objectMapper;

    public ScreenMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Maps one Screen entity to the response DTO used by the portal UI. */
    public ScreenDtos.ScreenResponse toDto(Screen s) {
        // 1. for offline screens, expose how long they have been silent
        Long offlineSeconds = null;
        if (s.getStatus() == Screen.Status.OFFLINE && s.getLastHeartbeatAt() != null) {
            offlineSeconds = Duration.between(s.getLastHeartbeatAt(), Instant.now()).getSeconds();
        }
        // 2. media state is stored as a raw JSON string; parse it (ignore malformed data)
        JsonNode mediaState = null;
        if (s.getMediaState() != null && !s.getMediaState().isBlank()) {
            try {
                mediaState = objectMapper.readTree(s.getMediaState());
            } catch (Exception ignored) {
            }
        }
        // 3. sign the "now playing" thumbnail link so it works for 1 hour without auth headers
        String currentItemThumbUrl = s.getCurrentItemMediaId() == null ? null
                : "/api/media/" + s.getCurrentItemMediaId() + "/thumb?"
                + com.screenpilot.signage.security.UrlSigner.instance()
                .signQuery("media:" + s.getCurrentItemMediaId(), 3600);
        return new ScreenDtos.ScreenResponse(
                s.getId(), s.getName(), s.getStoreName(), s.getCity(), s.getState(),
                s.getGroup() == null ? null : new UserDtos.GroupRef(s.getGroup().getId(), s.getGroup().getName()),
                s.getOrientation(), s.getResolution(), s.getLatitude(), s.getLongitude(),
                s.getStatus(), s.getLastHeartbeatAt(), offlineSeconds,
                s.getCurrentItemName(), s.getCurrentItemMediaId(), currentItemThumbUrl,
                s.getAppVersion(), s.isPaired(),
                s.getStorageUsedMb(), s.getStorageTotalMb(), mediaState, s.getCreatedAt());
    }
}
