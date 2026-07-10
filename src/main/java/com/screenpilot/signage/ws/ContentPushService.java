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

    public void schedulesUpdated(Collection<UUID> screenIds) {
        for (UUID screenId : new HashSet<>(screenIds)) {
            publisher.toScreen(screenId, Map.of("type", "SCHEDULES_UPDATED"));
        }
        if (!screenIds.isEmpty()) {
            log.info("Pushed SCHEDULES_UPDATED to {} screen(s)", new HashSet<>(screenIds).size());
        }
    }

    /** After a playlist edit commits, notify every screen currently scheduled with it. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void onPlaylistChanged(PlaylistService.PlaylistChangedEvent event) {
        Set<UUID> screenIds = new HashSet<>();
        for (Schedule schedule : scheduleRepository.findByPlaylistIdAndActiveTrue(event.playlistId())) {
            schedule.getScreens().forEach(s -> screenIds.add(s.getId()));
        }
        // playlists can also appear inside layout zones
        for (Schedule schedule : scheduleRepository.findAllByOrderByCreatedAtDesc()) {
            if (schedule.isActive() && schedule.getLayoutId() != null
                    && layoutUsesPlaylist(schedule.getLayoutId(), event.playlistId())) {
                schedule.getScreens().forEach(s -> screenIds.add(s.getId()));
            }
        }
        for (UUID screenId : screenIds) {
            publisher.toScreen(screenId, Map.of("type", "PLAYLIST_UPDATED", "playlistId", event.playlistId().toString()));
        }
        if (!screenIds.isEmpty()) {
            log.info("Playlist {} changed -> pushed live update to {} screen(s)", event.playlistId(), screenIds.size());
        }
    }

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

    private boolean layoutUsesPlaylist(UUID layoutId, UUID playlistId) {
        return layoutRepository.findWithZonesById(layoutId)
                .map(l -> l.getZones().stream()
                        .anyMatch(z -> z.getPlaylist() != null && playlistId.equals(z.getPlaylist().getId())))
                .orElse(false);
    }
}
