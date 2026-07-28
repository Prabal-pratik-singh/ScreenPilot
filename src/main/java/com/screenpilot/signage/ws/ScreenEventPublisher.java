package com.screenpilot.signage.ws;

import com.screenpilot.signage.dto.ScreenDtos;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Pushes real-time events to the portal (/topic/portal/screens)
 * and to individual players (/topic/screen/{id}).
 *
 * Deliberately a thin wrapper around SimpMessagingTemplate: it keeps every
 * topic name and message shape in this one file, so no other class ever
 * builds STOMP destination strings or ad-hoc payloads by hand.
 */
@Component
public class ScreenEventPublisher {

    // the one broadcast channel every open portal browser tab subscribes to
    public static final String PORTAL_TOPIC = "/topic/portal/screens";

    // Spring's handle to the STOMP broker: convertAndSend serializes the
    // payload to JSON and delivers it to every subscriber of the destination
    private final SimpMessagingTemplate template;

    public ScreenEventPublisher(SimpMessagingTemplate template) {
        this.template = template;
    }

    /**
     * Broadcast to the portal: a screen changed (status flip, now-playing, edits).
     * Message shape: { "type": "SCREEN_UPDATED", "screen": {...full screen DTO...} }
     * — the "type" field is what the frontend switches on to route the message.
     */
    public void screenUpdated(ScreenDtos.ScreenResponse screen) {
        template.convertAndSend(PORTAL_TOPIC, Map.of("type", "SCREEN_UPDATED", "screen", screen));
    }

    /**
     * Broadcast to the portal that a screen was deleted, so open tabs drop it.
     * Message shape: { "type": "SCREEN_REMOVED", "screenId": "..." }.
     */
    public void screenRemoved(UUID screenId) {
        template.convertAndSend(PORTAL_TOPIC, Map.of("type", "SCREEN_REMOVED", "screenId", screenId.toString()));
    }

    /**
     * Send to ONE player: each device subscribes only to its own
     * /topic/screen/{id}, so exactly that screen receives the payload
     * (content-update pings and remote commands travel this way).
     */
    public void toScreen(UUID screenId, Map<String, Object> payload) {
        template.convertAndSend("/topic/screen/" + screenId, payload);
    }
}
