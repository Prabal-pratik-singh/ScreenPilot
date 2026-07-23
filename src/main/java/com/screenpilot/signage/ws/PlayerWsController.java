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

    @MessageMapping("/player/heartbeat")
    public void heartbeat(@Payload PlayerDtos.HeartbeatRequest payload,
                          @Header(name = "x-device-token", required = false) String deviceToken) {
        if (deviceToken == null || deviceToken.isBlank()) {
            return;
        }
        Screen screen = screenRepository
                .findByDeviceToken(com.screenpilot.signage.security.TokenHasher.sha256Hex(deviceToken))
                .orElse(null);
        if (screen == null || !screen.isPaired()) {
            log.debug("Ignoring WS heartbeat with unknown device token");
            return;
        }
        heartbeatService.process(screen.getId(), payload);
    }
}
