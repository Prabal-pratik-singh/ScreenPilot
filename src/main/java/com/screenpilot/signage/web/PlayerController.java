package com.screenpilot.signage.web;

import com.screenpilot.signage.dto.PlayerDtos;
import com.screenpilot.signage.security.CurrentUser;
import com.screenpilot.signage.security.DevicePrincipal;
import com.screenpilot.signage.service.HeartbeatService;
import com.screenpilot.signage.service.PairingService;
import com.screenpilot.signage.service.PlaybackLogService;
import com.screenpilot.signage.service.PlayerConfigService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/player")
public class PlayerController {

    private final PairingService pairingService;
    private final HeartbeatService heartbeatService;
    private final PlayerConfigService playerConfigService;
    private final PlaybackLogService playbackLogService;
    private final com.screenpilot.signage.service.CommandService commandService;

    public PlayerController(PairingService pairingService, HeartbeatService heartbeatService,
                            PlayerConfigService playerConfigService, PlaybackLogService playbackLogService,
                            com.screenpilot.signage.service.CommandService commandService) {
        this.pairingService = pairingService;
        this.heartbeatService = heartbeatService;
        this.playerConfigService = playerConfigService;
        this.playbackLogService = playbackLogService;
        this.commandService = commandService;
    }

    @PostMapping("/pair/request")
    public PlayerDtos.PairCodeResponse requestPairing(@RequestBody(required = false) @Valid PlayerDtos.PairRequest request) {
        return pairingService.requestCode(request);
    }

    @GetMapping("/pair/poll/{code}")
    public PlayerDtos.PairPollResponse poll(@PathVariable String code) {
        return pairingService.poll(code);
    }

    @PostMapping("/heartbeat")
    public PlayerDtos.HeartbeatResponse heartbeat(@RequestBody(required = false) PlayerDtos.HeartbeatRequest request) {
        DevicePrincipal device = CurrentUser.device();
        return heartbeatService.process(device.screenId(), request);
    }

    @GetMapping("/config")
    public Map<String, Object> config() {
        DevicePrincipal device = CurrentUser.device();
        return playerConfigService.config(device.screenId());
    }

    /** Batched proof-of-play logs from the player (queued offline, synced on reconnect). */
    @PostMapping("/logs")
    public Map<String, Object> logs(@RequestBody PlaybackLogService.LogBatch batch) {
        DevicePrincipal device = CurrentUser.device();
        int saved = playbackLogService.saveBatch(device.screenId(), batch);
        return Map.of("saved", saved);
    }

    public record ScreenshotUpload(String imageBase64, java.util.UUID commandId) {
    }

    /** The player uploads a capture of what it is currently showing. */
    @PostMapping("/screenshot")
    public Map<String, Object> screenshot(@RequestBody ScreenshotUpload upload) {
        DevicePrincipal device = CurrentUser.device();
        commandService.saveScreenshot(device.screenId(), upload.imageBase64(), upload.commandId());
        return Map.of("ok", true);
    }

    @PostMapping("/commands/{commandId}/ack")
    public Map<String, Object> ackCommand(@PathVariable java.util.UUID commandId) {
        DevicePrincipal device = CurrentUser.device();
        commandService.ack(device.screenId(), commandId);
        return Map.of("ok", true);
    }
}
