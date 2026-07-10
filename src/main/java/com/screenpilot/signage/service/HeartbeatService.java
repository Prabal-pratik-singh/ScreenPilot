package com.screenpilot.signage.service;

import com.screenpilot.signage.config.AppProperties;
import com.screenpilot.signage.domain.Screen;
import com.screenpilot.signage.dto.PlayerDtos;
import com.screenpilot.signage.error.ApiException;
import com.screenpilot.signage.repo.ScreenRepository;
import com.screenpilot.signage.ws.ScreenEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class HeartbeatService {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatService.class);

    private final ScreenRepository screenRepository;
    private final ScreenMapper mapper;
    private final ScreenEventPublisher events;
    private final AppProperties props;
    private final com.screenpilot.signage.repo.ScreenStatusEventRepository statusEvents;

    public HeartbeatService(ScreenRepository screenRepository, ScreenMapper mapper,
                            ScreenEventPublisher events, AppProperties props,
                            com.screenpilot.signage.repo.ScreenStatusEventRepository statusEvents) {
        this.screenRepository = screenRepository;
        this.mapper = mapper;
        this.events = events;
        this.props = props;
        this.statusEvents = statusEvents;
    }

    @Transactional
    public PlayerDtos.HeartbeatResponse process(UUID screenId, PlayerDtos.HeartbeatRequest req) {
        Screen screen = screenRepository.findById(screenId)
                .orElseThrow(() -> ApiException.unauthorized("Screen no longer exists"));
        boolean wasOffline = screen.getStatus() != Screen.Status.ONLINE;

        screen.setStatus(Screen.Status.ONLINE);
        screen.setLastHeartbeatAt(Instant.now());
        if (req != null) {
            if (req.currentItemName() != null) {
                screen.setCurrentItemName(req.currentItemName());
            }
            screen.setCurrentItemMediaId(req.currentItemMediaId());
            if (req.appVersion() != null) {
                screen.setAppVersion(req.appVersion());
            }
            if (req.storageUsedMb() != null) {
                screen.setStorageUsedMb(req.storageUsedMb());
            }
            if (req.storageTotalMb() != null) {
                screen.setStorageTotalMb(req.storageTotalMb());
            }
            if (req.mediaState() != null) {
                screen.setMediaState(req.mediaState().toString());
            }
        }
        screenRepository.save(screen);
        // Every heartbeat refreshes "now playing"; the portal listens for these
        events.screenUpdated(mapper.toDto(screen));
        if (wasOffline) {
            statusEvents.save(new com.screenpilot.signage.domain.ScreenStatusEvent(
                    screen.getId(), Screen.Status.ONLINE, Instant.now()));
            log.info("Screen {} came online", screen.getName());
        }
        return new PlayerDtos.HeartbeatResponse(true, Instant.now());
    }

    /** Marks screens offline when heartbeats stop arriving. */
    @Scheduled(fixedDelay = 15000, initialDelay = 15000)
    @Transactional
    public void sweepOffline() {
        Instant cutoff = Instant.now().minus(Duration.ofSeconds(props.getPlayer().getOfflineAfterSeconds()));
        List<Screen> stale = screenRepository.findOnlineWithHeartbeatBefore(cutoff);
        for (Screen screen : stale) {
            screen.setStatus(Screen.Status.OFFLINE);
            screenRepository.save(screen);
            statusEvents.save(new com.screenpilot.signage.domain.ScreenStatusEvent(
                    screen.getId(), Screen.Status.OFFLINE, Instant.now()));
            events.screenUpdated(mapper.toDto(screen));
            log.info("Screen {} went offline (no heartbeat since {})", screen.getName(), screen.getLastHeartbeatAt());
        }
    }
}
