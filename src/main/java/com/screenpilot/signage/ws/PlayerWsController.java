package com.screenpilot.signage.ws;

import com.screenpilot.signage.domain.Screen;
import com.screenpilot.signage.dto.PlayerDtos;
import com.screenpilot.signage.repo.ScreenRepository;
import com.screenpilot.signage.service.HeartbeatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

/**
 * STOMP entry point for player heartbeats. The player sends to /app/player/heartbeat
 * with its device token in the "x-device-token" header; HTTP POST is the fallback path.
 */
@Controller
public class PlayerWsController {

    private static final Logger log = LoggerFactory.getLogger(PlayerWsController.class);

    private final ScreenRepository screenRepository;
    private final HeartbeatService heartbeatService;

    public PlayerWsController(ScreenRepository screenRepository, HeartbeatService heartbeatService) {
        this.screenRepository = screenRepository;
        this.heartbeatService = heartbeatService;
    }

    /**
     * Receives one heartbeat over the WebSocket. Because of the "/app" prefix in
     * WebSocketConfig, players SEND to /app/player/heartbeat and Spring routes the
     * message here. @Payload converts the JSON body into a HeartbeatRequest;
     * @Header pulls the custom STOMP header carrying the device token
     * (required = false so a missing header does not throw an exception).
     */
    @MessageMapping("/player/heartbeat")
    public void heartbeat(@Payload PlayerDtos.HeartbeatRequest payload,
                          @Header(name = "x-device-token", required = false) String deviceToken) {
        // no token, no service — heartbeats are fire-and-forget, so we simply
        // drop the message rather than send an error nobody is listening for
        if (deviceToken == null || deviceToken.isBlank()) {
            return;
        }
        // the DB stores only the SHA-256 hash of each device token, so hash the
        // presented token and look that hash up — plaintext is never persisted
        Screen screen = screenRepository
                .findByDeviceToken(com.screenpilot.signage.security.TokenHasher.sha256Hex(deviceToken))
                .orElse(null);
        // unknown or unpaired tokens are silently ignored (debug log only):
        // returning no error gives a token-guessing client zero feedback, and a
        // player whose screen was deleted just fails quietly instead of crashing
        if (screen == null || !screen.isPaired()) {
            log.debug("Ignoring WS heartbeat with unknown device token");
            return;
        }
        // delegate to the same service the HTTP heartbeat endpoint uses —
        // one processing path no matter how the heartbeat arrived
        heartbeatService.process(screen.getId(), payload);
    }
}
