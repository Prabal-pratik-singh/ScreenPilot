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

/**
 * The screen "pulse" tracker. Players POST a heartbeat every few seconds;
 * this service records it and flips status ONLINE. A @Scheduled sweep flips
 * screens back to OFFLINE when the pulse stops. Every status change also
 * lands in screen_status_events so uptime reports can be reconstructed later.
 */
@Service
public class HeartbeatService {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatService.class);

    // mapper turns entities into DTOs for the portal, events pushes them over
    // WebSocket, props holds the offline cutoff (90s by default), and
    // statusEvents stores ONLINE/OFFLINE transitions for the uptime history
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

    /** Handles one heartbeat: marks the screen online and copies over the telemetry it sent. */
    @Transactional
    public PlayerDtos.HeartbeatResponse process(UUID screenId, PlayerDtos.HeartbeatRequest req) {
        // 401 (not 404) because the caller is a device using its token: if the
        // screen row is gone, the token is dead and the player should re-pair
        Screen screen = screenRepository.findById(screenId)
                .orElseThrow(() -> ApiException.unauthorized("Screen no longer exists"));
        // remember the previous state so we can log an ONLINE transition below
        // (must be captured BEFORE the status is overwritten two lines down)
        boolean wasOffline = screen.getStatus() != Screen.Status.ONLINE;

        // 1. mark online and stamp the heartbeat time
        screen.setStatus(Screen.Status.ONLINE);
        screen.setLastHeartbeatAt(Instant.now());
        // 2. copy optional telemetry: now-playing item, app version, storage, media cache state
        if (req != null) {
            // most fields are null-guarded: a heartbeat that omits a value must
            // not wipe what we already know (partial update)
            if (req.currentItemName() != null) {
                screen.setCurrentItemName(req.currentItemName());
            }
            // deliberately NOT null-guarded: when the player stops showing a
            // media item, the incoming null overwrites the old id so the
            // "now playing" pointer never sticks around stale
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
        // 3. record the OFFLINE -> ONLINE transition for the uptime history
        // only TRANSITIONS are stored, not every heartbeat — that keeps the
        // table tiny and makes uptime math cheap: uptime is just the gaps
        // between an ONLINE event and the next OFFLINE event
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
        // scheduled every 15s: fixedDelay counts from the END of the previous
        // run (so runs never pile up), and initialDelay gives the app 15s to
        // finish starting before the first sweep
        // 1. anything still ONLINE whose last heartbeat is older than the cutoff is stale
        // cutoff = now minus offlineAfterSeconds (default 90s = three missed
        // 30s heartbeats — enough slack that one lost packet doesn't flap the status)
        Instant cutoff = Instant.now().minus(Duration.ofSeconds(props.getPlayer().getOfflineAfterSeconds()));
        List<Screen> stale = screenRepository.findOnlineWithHeartbeatBefore(cutoff);
        for (Screen screen : stale) {
            // 2. flip to OFFLINE, log the status event, and push the change to the portal live view
            screen.setStatus(Screen.Status.OFFLINE);
            screenRepository.save(screen);
            // the OFFLINE edge closes the uptime interval opened by the last ONLINE event
            statusEvents.save(new com.screenpilot.signage.domain.ScreenStatusEvent(
                    screen.getId(), Screen.Status.OFFLINE, Instant.now()));
            // portal dashboards update instantly instead of waiting for a refresh
            events.screenUpdated(mapper.toDto(screen));
            log.info("Screen {} went offline (no heartbeat since {})", screen.getName(), screen.getLastHeartbeatAt());
        }
    }
}
