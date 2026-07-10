package com.screenpilot.signage.ws;

import com.screenpilot.signage.dto.ScreenDtos;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Pushes real-time events to the portal (/topic/portal/screens)
 * and to individual players (/topic/screen/{id}).
 */
@Component
public class ScreenEventPublisher {

    public static final String PORTAL_TOPIC = "/topic/portal/screens";

    private final SimpMessagingTemplate template;

    public ScreenEventPublisher(SimpMessagingTemplate template) {
        this.template = template;
    }

    public void screenUpdated(ScreenDtos.ScreenResponse screen) {
        template.convertAndSend(PORTAL_TOPIC, Map.of("type", "SCREEN_UPDATED", "screen", screen));
    }

    public void screenRemoved(UUID screenId) {
        template.convertAndSend(PORTAL_TOPIC, Map.of("type", "SCREEN_REMOVED", "screenId", screenId.toString()));
    }

    public void toScreen(UUID screenId, Map<String, Object> payload) {
        template.convertAndSend("/topic/screen/" + screenId, payload);
    }
}
