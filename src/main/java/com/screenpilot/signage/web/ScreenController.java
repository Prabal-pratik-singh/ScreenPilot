package com.screenpilot.signage.web;

import com.screenpilot.signage.domain.ScreenCommand;
import com.screenpilot.signage.dto.ScreenDtos;
import com.screenpilot.signage.service.CommandService;
import com.screenpilot.signage.service.PairingService;
import com.screenpilot.signage.service.PlayerConfigService;
import com.screenpilot.signage.service.ScreenService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/screens")
public class ScreenController {

    private final ScreenService screenService;
    private final PairingService pairingService;
    private final PlayerConfigService playerConfigService;
    private final CommandService commandService;

    public ScreenController(ScreenService screenService, PairingService pairingService,
                            PlayerConfigService playerConfigService, CommandService commandService) {
        this.screenService = screenService;
        this.pairingService = pairingService;
        this.playerConfigService = playerConfigService;
        this.commandService = commandService;
    }

    public record CommandRequest(@NotNull ScreenCommand.Command command) {
    }

    @GetMapping
    @PreAuthorize("hasRole('VIEWER')")
    public List<ScreenDtos.ScreenResponse> list(@RequestParam(required = false) UUID groupId,
                                                @RequestParam(required = false) String state,
                                                @RequestParam(required = false) String city,
                                                @RequestParam(required = false) String status,
                                                @RequestParam(required = false) String search) {
        return screenService.list(groupId, state, city, status, search);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('VIEWER')")
    public ScreenDtos.ScreenResponse get(@PathVariable UUID id) {
        return screenService.get(id);
    }

    /** What this screen should be playing: active schedules + required media (drives the download-status panel). */
    @GetMapping("/{id}/content")
    @PreAuthorize("hasRole('VIEWER')")
    public Map<String, Object> content(@PathVariable UUID id) {
        screenService.getAccessible(id);
        return playerConfigService.config(id);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ScreenDtos.ScreenResponse create(@Valid @RequestBody ScreenDtos.SaveScreenRequest request) {
        return screenService.create(request);
    }

    @PostMapping("/pair")
    @PreAuthorize("hasRole('ADMIN')")
    public ScreenDtos.ScreenResponse pair(@Valid @RequestBody ScreenDtos.PairScreenRequest request) {
        return pairingService.pair(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ScreenDtos.ScreenResponse update(@PathVariable UUID id, @Valid @RequestBody ScreenDtos.SaveScreenRequest request) {
        return screenService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable UUID id) {
        screenService.delete(id);
    }

    @PostMapping("/bulk-group")
    @PreAuthorize("hasRole('ADMIN')")
    public void bulkGroup(@Valid @RequestBody ScreenDtos.BulkGroupRequest request) {
        screenService.bulkAssignGroup(request);
    }

    /** Remote commands: RELOAD, CLEAR_CACHE, SCREENSHOT — pushed to the player over WebSocket. */
    @PostMapping("/{id}/commands")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> sendCommand(@PathVariable UUID id, @Valid @RequestBody CommandRequest request) {
        ScreenCommand cmd = commandService.send(id, request.command());
        return Map.of("id", cmd.getId(), "command", cmd.getCommand().name(), "status", cmd.getStatus().name());
    }

    @GetMapping("/{id}/commands")
    @PreAuthorize("hasRole('VIEWER')")
    public List<Map<String, Object>> commandHistory(@PathVariable UUID id) {
        return commandService.history(id).stream()
                .map(c -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("id", c.getId());
                    m.put("command", c.getCommand().name());
                    m.put("status", c.getStatus().name());
                    m.put("createdAt", c.getCreatedAt());
                    m.put("completedAt", c.getCompletedAt());
                    return m;
                })
                .toList();
    }

    /**
     * Latest screenshot captured by the player. Served with a signed URL
     * (browsers' <img> tags cannot send the Authorization header), so the
     * signature IS the access control here.
     */
    @GetMapping("/{id}/screenshot")
    public ResponseEntity<Resource> screenshot(@PathVariable UUID id,
                                               @RequestParam(required = false) Long exp,
                                               @RequestParam(required = false) String sig) {
        if (!com.screenpilot.signage.security.UrlSigner.instance().verify("screenshot:" + id, exp, sig)) {
            throw com.screenpilot.signage.error.ApiException.forbidden("This screenshot link is invalid or has expired");
        }
        Resource res = commandService.latestScreenshotUnchecked(id);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(res);
    }

    /** Authenticated endpoint that mints a short-lived signed link for the <img> tag. */
    @GetMapping("/{id}/screenshot-link")
    @PreAuthorize("hasRole('VIEWER')")
    public Map<String, String> screenshotLink(@PathVariable UUID id) {
        screenService.getAccessible(id);
        String url = "/api/screens/" + id + "/screenshot?"
                + com.screenpilot.signage.security.UrlSigner.instance().signQuery("screenshot:" + id, 900);
        return Map.of("url", url);
    }
}
