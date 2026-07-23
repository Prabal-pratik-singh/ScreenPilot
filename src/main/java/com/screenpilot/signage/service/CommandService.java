package com.screenpilot.signage.service;

import com.screenpilot.signage.domain.Screen;
import com.screenpilot.signage.domain.ScreenCommand;
import com.screenpilot.signage.error.ApiException;
import com.screenpilot.signage.repo.ScreenCommandRepository;
import com.screenpilot.signage.security.CurrentUser;
import com.screenpilot.signage.storage.StorageService;
import com.screenpilot.signage.ws.ScreenEventPublisher;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class CommandService {

    private final ScreenCommandRepository commandRepository;
    private final ScreenService screenService;
    private final ScreenEventPublisher events;
    private final StorageService storage;

    public CommandService(ScreenCommandRepository commandRepository, ScreenService screenService,
                          ScreenEventPublisher events, StorageService storage) {
        this.commandRepository = commandRepository;
        this.screenService = screenService;
        this.events = events;
        this.storage = storage;
    }

    @Transactional
    public ScreenCommand send(UUID screenId, ScreenCommand.Command command) {
        Screen screen = screenService.getAccessible(screenId);
        ScreenCommand cmd = new ScreenCommand(screen.getId(), command, CurrentUser.get().id());
        commandRepository.save(cmd);
        events.toScreen(screen.getId(), Map.of(
                "type", "COMMAND",
                "command", command.name(),
                "commandId", cmd.getId().toString()));
        return cmd;
    }

    @Transactional(readOnly = true)
    public List<ScreenCommand> history(UUID screenId) {
        screenService.getAccessible(screenId);
        return commandRepository.findTop10ByScreenIdOrderByCreatedAtDesc(screenId);
    }

    @Transactional
    public void ack(UUID screenId, UUID commandId) {
        commandRepository.findById(commandId).ifPresent(cmd -> {
            if (cmd.getScreenId().equals(screenId) && cmd.getStatus() == ScreenCommand.Status.SENT) {
                cmd.setStatus(cmd.getCommand() == ScreenCommand.Command.SCREENSHOT
                        ? ScreenCommand.Status.ACKED : ScreenCommand.Status.COMPLETED);
                cmd.setCompletedAt(cmd.getCommand() == ScreenCommand.Command.SCREENSHOT ? null : Instant.now());
                commandRepository.save(cmd);
            }
        });
    }

    /** Stores the uploaded screenshot as the screen's latest and completes the command. */
    @Transactional
    public void saveScreenshot(UUID screenId, String imageBase64, UUID commandId) {
        if (imageBase64 == null || imageBase64.isBlank()) {
            throw ApiException.badRequest("No image received");
        }
        String base64 = imageBase64.contains(",") ? imageBase64.substring(imageBase64.indexOf(',') + 1) : imageBase64;
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Invalid image data");
        }
        if (bytes.length > 5 * 1024 * 1024) {
            throw ApiException.badRequest("Screenshot too large");
        }
        try {
            String key = storage.store(new ByteArrayInputStream(bytes), "screenshots", screenId + ".jpg");
            if (commandId != null) {
                commandRepository.findById(commandId).ifPresent(cmd -> {
                    if (cmd.getScreenId().equals(screenId)) {
                        cmd.setStatus(ScreenCommand.Status.COMPLETED);
                        cmd.setCompletedAt(Instant.now());
                        cmd.setResultPath(key);
                        commandRepository.save(cmd);
                    }
                });
            }
        } catch (IOException e) {
            throw new ApiException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, "Could not store screenshot");
        }
    }

    /**
     * Access control happens via the signed URL (HMAC), not the session —
     * <img> tags cannot send Authorization headers.
     */
    @Transactional(readOnly = true)
    public Resource latestScreenshotUnchecked(UUID screenId) {
        Resource res = storage.loadAsResource("screenshots/" + screenId + ".jpg");
        if (!res.exists()) {
            throw ApiException.notFound("No screenshot has been captured for this screen yet");
        }
        return res;
    }
}
