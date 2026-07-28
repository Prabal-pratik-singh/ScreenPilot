package com.screenpilot.signage.controller;

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

/**
 * Endpoints the TV player app talks to, all under /api/player. Two access levels:
 * the /pair/** endpoints are public (a brand-new screen has no credentials yet),
 * while every other endpoint requires the DEVICE role — granted by DeviceTokenFilter
 * when the player sends a valid X-Device-Token header. Inside a method,
 * CurrentUser.device() reveals WHICH paired screen is calling.
 */
@RestController
@RequestMapping("/api/player")
public class PlayerController {

    // One small service per player concern: pairing, liveness, config, logs, commands.
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

    /**
     * Pairing step 1: an unpaired player asks for a short code to display on the TV.
     * Public on purpose (see SecurityConfig) — the device owns no token yet, so it
     * has no way to authenticate. The optional body carries device info to show
     * alongside the code in the portal.
     */
    @PostMapping("/pair/request")
    public PlayerDtos.PairCodeResponse requestPairing(@RequestBody(required = false) @Valid PlayerDtos.PairRequest request) {
        return pairingService.requestCode(request);
    }

    /**
     * Pairing step 2: the player repeatedly polls its code while an admin claims it
     * in the portal. Once claimed, the response status flips to PAIRED and includes
     * the permanent device token the player will send on all future requests (as
     * the X-Device-Token header). Also public, for the same reason as step 1.
     */
    @GetMapping("/pair/poll/{code}")
    public PlayerDtos.PairPollResponse poll(@PathVariable String code) {
        return pairingService.poll(code);
    }

    /**
     * The player's periodic "I'm alive" ping, carrying health details (what is
     * playing, app version, storage use). The screen id comes from the authenticated
     * device token — never from the request body — so one device cannot report on
     * behalf of another. The reply is a simple ok flag plus the server time.
     */
    @PostMapping("/heartbeat")
    public PlayerDtos.HeartbeatResponse heartbeat(@RequestBody(required = false) PlayerDtos.HeartbeatRequest request) {
        DevicePrincipal device = CurrentUser.device();
        return heartbeatService.process(device.screenId(), request);
    }

    /**
     * The full playback instructions for THIS screen (assigned content, schedules,
     * settings). Returned as a loose Map because the player consumes it directly
     * as JSON rather than through a typed DTO.
     */
    @GetMapping("/config")
    public Map<String, Object> config() {
        DevicePrincipal device = CurrentUser.device();
        return playerConfigService.config(device.screenId());
    }

    /** Batched proof-of-play logs from the player (queued offline, synced on reconnect). */
    @PostMapping("/logs")
    public Map<String, Object> logs(@RequestBody PlaybackLogService.LogBatch batch) {
        DevicePrincipal device = CurrentUser.device();
        // Store the whole batch under the authenticated screen's id, and echo back
        // how many rows landed so the player knows the sync succeeded.
        int saved = playbackLogService.saveBatch(device.screenId(), batch);
        return Map.of("saved", saved);
    }

    // Request body for /screenshot: the capture as base64 text, plus (optionally)
    // the id of the remote command that asked for it.
    public record ScreenshotUpload(String imageBase64, java.util.UUID commandId) {
    }

    /** The player uploads a capture of what it is currently showing. */
    @PostMapping("/screenshot")
    public Map<String, Object> screenshot(@RequestBody ScreenshotUpload upload) {
        DevicePrincipal device = CurrentUser.device();
        // The service decodes and validates the image, stores it as the screen's
        // latest capture, and completes the triggering command if an id was given.
        commandService.saveScreenshot(device.screenId(), upload.imageBase64(), upload.commandId());
        return Map.of("ok", true);
    }

    /**
     * The player confirms it received/executed a remote command (reload, screenshot,
     * ...). Passing the authenticated screen id along lets the service verify the
     * command really was addressed to THIS device before updating its status.
     */
    @PostMapping("/commands/{commandId}/ack")
    public Map<String, Object> ackCommand(@PathVariable java.util.UUID commandId) {
        DevicePrincipal device = CurrentUser.device();
        commandService.ack(device.screenId(), commandId);
        return Map.of("ok", true);
    }
}
