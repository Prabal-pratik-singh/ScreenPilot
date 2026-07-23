package com.screenpilot.signage.service;

import com.screenpilot.signage.domain.Screen;
import com.screenpilot.signage.dto.ScreenDtos;
import com.screenpilot.signage.dto.UserDtos;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component
public class ScreenMapper {

    private final ObjectMapper objectMapper;

    public ScreenMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ScreenDtos.ScreenResponse toDto(Screen s) {
        Long offlineSeconds = null;
        if (s.getStatus() == Screen.Status.OFFLINE && s.getLastHeartbeatAt() != null) {
            offlineSeconds = Duration.between(s.getLastHeartbeatAt(), Instant.now()).getSeconds();
        }
        JsonNode mediaState = null;
        if (s.getMediaState() != null && !s.getMediaState().isBlank()) {
            try {
                mediaState = objectMapper.readTree(s.getMediaState());
            } catch (Exception ignored) {
            }
        }
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
