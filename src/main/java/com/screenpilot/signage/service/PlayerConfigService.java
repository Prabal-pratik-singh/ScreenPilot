package com.screenpilot.signage.service;

import com.screenpilot.signage.domain.*;
import com.screenpilot.signage.dto.PlaylistDtos;
import com.screenpilot.signage.error.ApiException;
import com.screenpilot.signage.repo.LayoutRepository;
import com.screenpilot.signage.repo.MediaAssetRepository;
import com.screenpilot.signage.repo.ScheduleRepository;
import com.screenpilot.signage.repo.ScreenRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Assembles everything a player needs to run: identity, the active schedules
 * with playlist/layout content, and the flat list of media files to cache offline.
 */
@Service
public class PlayerConfigService {

    private final ScreenRepository screenRepository;
    private final ScheduleRepository scheduleRepository;
    private final LayoutRepository layoutRepository;
    private final MediaAssetRepository mediaRepository;
    private final ObjectMapper objectMapper;

    public PlayerConfigService(ScreenRepository screenRepository, ScheduleRepository scheduleRepository,
                               LayoutRepository layoutRepository, MediaAssetRepository mediaRepository,
                               ObjectMapper objectMapper) {
        this.screenRepository = screenRepository;
        this.scheduleRepository = scheduleRepository;
        this.layoutRepository = layoutRepository;
        this.mediaRepository = mediaRepository;
        this.objectMapper = objectMapper;
    }

    /** Builds the full JSON config one screen downloads: identity, schedules, and media to cache. */
    @Transactional(readOnly = true)
    public Map<String, Object> config(UUID screenId) {
        // 401 rather than 404: the caller is a device authenticating by token,
        // and a missing screen row means that token is dead — time to re-pair
        Screen screen = screenRepository.findById(screenId)
                .orElseThrow(() -> ApiException.unauthorized("Screen no longer exists"));

        // 1. screen identity block + how often to heartbeat
        Map<String, Object> config = new LinkedHashMap<>();
        Map<String, Object> screenInfo = new LinkedHashMap<>();
        screenInfo.put("id", screen.getId());
        screenInfo.put("name", screen.getName());
        screenInfo.put("storeName", screen.getStoreName());
        screenInfo.put("city", screen.getCity());
        screenInfo.put("state", screen.getState());
        screenInfo.put("orientation", screen.getOrientation().name());
        screenInfo.put("resolution", screen.getResolution());
        config.put("screen", screenInfo);
        // the server dictates the heartbeat cadence (30s) so it can be tuned
        // centrally without shipping a new player build
        config.put("heartbeatIntervalMs", 30000);

        // 2. expand every active schedule; requiredMedia collects (deduped) every
        //    asset referenced anywhere so the player can cache it for offline play
        Map<UUID, MediaAsset> requiredMedia = new LinkedHashMap<>();
        List<Map<String, Object>> schedules = new ArrayList<>();
        // the query already filters to active=true schedules targeting this screen
        for (Schedule s : scheduleRepository.findActiveForScreen(screenId)) {
            // additionally skip schedules whose date range already ended (IST):
            // the player would never play them, so don't make it cache their media
            if (s.getDateTo() != null && s.getDateTo().isBefore(TimeUtil.todayIST())) {
                continue; // expired
            }
            Map<String, Object> dto = new LinkedHashMap<>();
            dto.put("id", s.getId());
            dto.put("name", s.getName());
            dto.put("contentType", s.getContentType().name());
            dto.put("allDay", s.isAllDay());
            dto.put("startTime", s.getStartTime() == null ? null : s.getStartTime().toString());
            dto.put("endTime", s.getEndTime() == null ? null : s.getEndTime().toString());
            dto.put("daysOfWeek", TimeUtil.parseDays(s.getDaysOfWeek()));
            dto.put("dateFrom", s.getDateFrom() == null ? null : s.getDateFrom().toString());
            dto.put("dateTo", s.getDateTo() == null ? null : s.getDateTo().toString());
            // priority (10 = timed, 0 = all-day) lets the player pick the winner
            // locally when several schedules are live at the same moment
            dto.put("priority", s.getPriority());
            dto.put("updatedAt", s.getUpdatedAt() == null ? null : s.getUpdatedAt().toString());
            // 3. inline the schedule's content: a playlist or a multi-zone layout
            if (s.getContentType() == Schedule.ContentType.PLAYLIST && s.getPlaylist() != null) {
                dto.put("playlist", playlistDto(s.getPlaylist(), requiredMedia));
            }
            if (s.getContentType() == Schedule.ContentType.LAYOUT && s.getLayoutId() != null) {
                layoutRepository.findWithZonesById(s.getLayoutId())
                        .ifPresent(layout -> dto.put("layout", layoutDto(layout, requiredMedia)));
            }
            schedules.add(dto);
        }
        config.put("schedules", schedules);

        // 4. the flat download list, each entry carrying an HMAC-signed URL
        List<Map<String, Object>> media = new ArrayList<>();
        for (MediaAsset m : requiredMedia.values()) {
            Map<String, Object> md = new LinkedHashMap<>();
            md.put("id", m.getId());
            md.put("name", m.getName());
            md.put("type", m.getType().name());
            md.put("mimeType", m.getMimeType());
            md.put("sizeBytes", m.getSizeBytes());
            md.put("durationSeconds", m.getDurationSeconds());
            // the HMAC signature in the query string is the player's "ticket":
            // it can download the file with no login or session — the server
            // only verifies the signature and its expiry stamp
            // signed download link; players refresh config often, so 24h TTL is generous
            md.put("url", "/api/media/" + m.getId() + "/file?"
                    + com.screenpilot.signage.security.UrlSigner.instance()
                    .signQuery("media:" + m.getId(), 24 * 3600));
            media.add(md);
        }
        config.put("requiredMedia", media);
        return config;
    }

    // serializes a layout with its zones; any media a zone needs is added to requiredMedia
    private Map<String, Object> layoutDto(Layout layout, Map<UUID, MediaAsset> requiredMedia) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", layout.getId());
        dto.put("name", layout.getName());
        dto.put("orientation", layout.getOrientation().name());
        // each zone is a rectangle on the screen: x/y/w/h geometry plus a z
        // stacking order, with its own content (playlist, ticker, logo, ...)
        List<Map<String, Object>> zones = new ArrayList<>();
        for (LayoutZone zone : layout.getZones()) {
            Map<String, Object> zdto = new LinkedHashMap<>();
            zdto.put("id", zone.getId());
            zdto.put("type", zone.getType().name());
            zdto.put("x", zone.getX());
            zdto.put("y", zone.getY());
            zdto.put("w", zone.getW());
            zdto.put("h", zone.getH());
            zdto.put("z", zone.getZ());
            if (zone.getPlaylist() != null) {
                zdto.put("playlist", playlistDto(zone.getPlaylist(), requiredMedia));
            }
            // zone config is stored as a raw JSON string; parse it here so the
            // player receives a real JSON object instead of an escaped string
            if (zone.getConfig() != null) {
                try {
                    JsonNode config = objectMapper.readTree(zone.getConfig());
                    zdto.put("config", config);
                    // a logo zone references a media asset the player must cache
                    if (zone.getType() == LayoutZone.Type.LOGO && config.hasNonNull("mediaId")) {
                        try {
                            UUID mediaId = UUID.fromString(config.get("mediaId").asText());
                            mediaRepository.findById(mediaId)
                                    .filter(m -> !m.isDeleted())
                                    .ifPresent(m -> requiredMedia.putIfAbsent(m.getId(), m));
                        } catch (IllegalArgumentException ignored) {
                            // mediaId wasn't a valid UUID — skip it, nothing to cache
                        }
                    }
                } catch (Exception ignored) {
                    // unparseable zone config JSON: leave the config out rather
                    // than fail building the whole player config
                }
            }
            zones.add(zdto);
        }
        dto.put("zones", zones);
        return dto;
    }

    // serializes a playlist's items; each media item is also registered in requiredMedia
    private Map<String, Object> playlistDto(Playlist source, Map<UUID, MediaAsset> requiredMedia) {
        Map<String, Object> playlist = new LinkedHashMap<>();
        playlist.put("id", source.getId());
        playlist.put("name", source.getName());
        List<Map<String, Object>> items = new ArrayList<>();
        for (PlaylistItem item : source.getItems()) {
            // soft-deleted media is filtered out so the player is never told
            // to fetch a file that would only come back as a 404
            if (item.getMedia() != null && item.getMedia().isDeleted()) {
                continue; // deleted assets silently drop out of the loop
            }
            Map<String, Object> it = new LinkedHashMap<>();
            it.put("id", item.getId());
            it.put("itemType", item.getItemType().name());
            it.put("title", item.getTitle());
            it.put("url", item.getUrl());
            it.put("durationSeconds", item.getDurationSeconds());
            // effectiveDuration = what the player should actually use: videos
            // play their full length, other items use their configured seconds
            // (with sensible defaults when nothing was set)
            it.put("effectiveDurationSeconds", PlaylistDtos.effectiveDuration(item));
            if (item.getMedia() != null) {
                MediaAsset m = item.getMedia();
                // register the asset for offline caching; putIfAbsent dedupes
                // when the same file appears in several playlists or zones
                requiredMedia.putIfAbsent(m.getId(), m);
                Map<String, Object> md = new LinkedHashMap<>();
                md.put("id", m.getId());
                md.put("name", m.getName());
                md.put("type", m.getType().name());
                md.put("mimeType", m.getMimeType());
                md.put("durationSeconds", m.getDurationSeconds());
                it.put("media", md);
            }
            items.add(it);
        }
        playlist.put("items", items);
        return playlist;
    }
}
