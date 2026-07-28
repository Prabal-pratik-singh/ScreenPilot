package com.screenpilot.signage.ws;

import com.screenpilot.signage.domain.Schedule;
import com.screenpilot.signage.repo.ScheduleRepository;
import com.screenpilot.signage.service.PlaylistService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Pushes content-change notifications to players. Players react by
 * refetching /api/player/config and hot-swapping their loop.
 */
@Component
public class ContentPushService {

    private static final Logger log = LoggerFactory.getLogger(ContentPushService.class);

    private final ScreenEventPublisher publisher;
    private final ScheduleRepository scheduleRepository;
    private final com.screenpilot.signage.repo.LayoutRepository layoutRepository;

    public ContentPushService(ScreenEventPublisher publisher, ScheduleRepository scheduleRepository,
                              com.screenpilot.signage.repo.LayoutRepository layoutRepository) {
        this.publisher = publisher;
        this.scheduleRepository = scheduleRepository;
        this.layoutRepository = layoutRepository;
    }

    /**
     * Pings every affected screen after a schedule change. Callers (ScheduleService)
     * pass the union of the screens targeted BEFORE and AFTER the edit, so a screen
     * that was just removed from a schedule also gets a ping and stops playing it.
     * The message carries no data — players react by refetching /api/player/config,
     * which is always the single source of truth.
     */
    public void schedulesUpdated(Collection<UUID> screenIds) {
        // wrap in a HashSet so no screen is pinged twice for one change
        for (UUID screenId : new HashSet<>(screenIds)) {
            publisher.toScreen(screenId, Map.of("type", "SCHEDULES_UPDATED"));
        }
        if (!screenIds.isEmpty()) {
            log.info("Pushed SCHEDULES_UPDATED to {} screen(s)", new HashSet<>(screenIds).size());
        }
    }

    // AFTER_COMMIT: this listener fires only once the editing transaction has
    // COMMITTED. Pushing any earlier would be a race — a player could refetch
    // its config before the change hits the database, read the OLD content,
    // and then never hear about the new version at all.
    // REQUIRES_NEW: by commit time the original transaction is finished, so
    // this method opens its own fresh read-only transaction to run queries
    // and lazily load each schedule's screens.
    /** After a playlist edit commits, notify every screen currently scheduled with it. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void onPlaylistChanged(PlaylistService.PlaylistChangedEvent event) {
        Set<UUID> screenIds = new HashSet<>();
        // fan-out step 1: schedules that play this playlist directly
        for (Schedule schedule : scheduleRepository.findByPlaylistIdAndActiveTrue(event.playlistId())) {
            schedule.getScreens().forEach(s -> screenIds.add(s.getId()));
        }
        // playlists can also appear inside layout zones
        // fan-out step 2: walk every active layout schedule and check whether
        // any zone of its layout embeds this playlist (indirect usage)
        for (Schedule schedule : scheduleRepository.findAllByOrderByCreatedAtDesc()) {
            if (schedule.isActive() && schedule.getLayoutId() != null
                    && layoutUsesPlaylist(schedule.getLayoutId(), event.playlistId())) {
                schedule.getScreens().forEach(s -> screenIds.add(s.getId()));
            }
        }
        // ping each collected screen; the playlistId lets the player know
        // exactly which playlist to refresh
        for (UUID screenId : screenIds) {
            publisher.toScreen(screenId, Map.of("type", "PLAYLIST_UPDATED", "playlistId", event.playlistId().toString()));
        }
        if (!screenIds.isEmpty()) {
            log.info("Playlist {} changed -> pushed live update to {} screen(s)", event.playlistId(), screenIds.size());
        }
    }

    // same AFTER_COMMIT + REQUIRES_NEW pattern as onPlaylistChanged above;
    // simpler because layouts are only ever referenced directly by schedules
    /** After a layout edit commits, notify screens running it. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void onLayoutChanged(com.screenpilot.signage.service.LayoutService.LayoutChangedEvent event) {
        Set<UUID> screenIds = new HashSet<>();
        for (Schedule schedule : scheduleRepository.findByLayoutIdAndActiveTrue(event.layoutId())) {
            schedule.getScreens().forEach(s -> screenIds.add(s.getId()));
        }
        for (UUID screenId : screenIds) {
            publisher.toScreen(screenId, Map.of("type", "LAYOUT_UPDATED", "layoutId", event.layoutId().toString()));
        }
        if (!screenIds.isEmpty()) {
            log.info("Layout {} changed -> pushed live update to {} screen(s)", event.layoutId(), screenIds.size());
        }
    }

    // loads the layout with its zones and answers: does any zone play this playlist?
    private boolean layoutUsesPlaylist(UUID layoutId, UUID playlistId) {
        return layoutRepository.findWithZonesById(layoutId)
                .map(l -> l.getZones().stream()
                        .anyMatch(z -> z.getPlaylist() != null && playlistId.equals(z.getPlaylist().getId())))
                .orElse(false);
    }
}
