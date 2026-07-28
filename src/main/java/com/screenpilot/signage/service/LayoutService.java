package com.screenpilot.signage.service;

import com.screenpilot.signage.domain.Layout;
import com.screenpilot.signage.domain.LayoutZone;
import com.screenpilot.signage.domain.Playlist;
import com.screenpilot.signage.dto.LayoutDtos;
import com.screenpilot.signage.error.ApiException;
import com.screenpilot.signage.repo.LayoutRepository;
import com.screenpilot.signage.repo.PlaylistRepository;
import com.screenpilot.signage.repo.ScheduleRepository;
import com.screenpilot.signage.repo.UserRepository;
import com.screenpilot.signage.security.CurrentUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * CRUD for multi-zone layouts: a layout splits the screen into zones
 * (MEDIA / TICKER / WIDGET / LOGO) positioned in percentages, so the same
 * layout scales to any resolution. Zone config is stored as free-form JSON.
 */
@Service
public class LayoutService {

    /** Fired after a layout's zones change so players can hot-reload. */
    public record LayoutChangedEvent(UUID layoutId) {
    }

    private final LayoutRepository layoutRepository;
    private final PlaylistRepository playlistRepository;
    private final ScheduleRepository scheduleRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher events;

    public LayoutService(LayoutRepository layoutRepository, PlaylistRepository playlistRepository,
                         ScheduleRepository scheduleRepository, UserRepository userRepository,
                         ObjectMapper objectMapper, ApplicationEventPublisher events) {
        this.layoutRepository = layoutRepository;
        this.playlistRepository = playlistRepository;
        this.scheduleRepository = scheduleRepository;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
        this.events = events;
    }

    /** All layouts, most recently updated first. */
    @Transactional(readOnly = true)
    public List<LayoutDtos.LayoutResponse> list() {
        return layoutRepository.findAllByOrderByUpdatedAtDesc().stream().map(this::toDto).toList();
    }

    /** One layout with its zones. */
    @Transactional(readOnly = true)
    public LayoutDtos.LayoutResponse get(UUID id) {
        return toDto(getEntity(id));
    }

    /** Loads the entity with zones eagerly fetched, or throws 404. */
    @Transactional(readOnly = true)
    public Layout getEntity(UUID id) {
        return layoutRepository.findWithZonesById(id)
                .orElseThrow(() -> ApiException.notFound("Layout not found"));
    }

    /** Creates a layout pre-filled with zones from the chosen preset (default FULLSCREEN). */
    @Transactional
    public LayoutDtos.LayoutResponse create(LayoutDtos.CreateLayoutRequest req) {
        Layout layout = new Layout(req.name().trim(), req.orientation(),
                userRepository.findById(CurrentUser.get().id()).orElse(null));
        applyPreset(layout, req.preset());
        return toDto(layoutRepository.save(layout));
    }

    /** Saves the layout editor state: replaces all zones with the submitted set. */
    @Transactional
    public LayoutDtos.LayoutResponse update(UUID id, LayoutDtos.SaveLayoutRequest req) {
        Layout layout = getEntity(id);
        layout.setName(req.name().trim());
        layout.setOrientation(req.orientation());
        // 1. full replace: drop existing zones and rebuild from the request
        layout.getZones().clear();
        int index = 0;
        for (LayoutDtos.SaveZoneRequest zoneReq : req.zones()) {
            LayoutZone zone = new LayoutZone(layout, zoneReq.type());
            // 2. coordinates are percentages — clamp to 0..100 so nothing renders off-screen
            zone.setX(clampPct(zoneReq.x()));
            zone.setY(clampPct(zoneReq.y()));
            zone.setW(clampPct(zoneReq.w()));
            zone.setH(clampPct(zoneReq.h()));
            zone.setZ(zoneReq.z() != null ? zoneReq.z() : ++index);
            // 3. MEDIA zones may reference the playlist they should loop
            if (zoneReq.type() == LayoutZone.Type.MEDIA && zoneReq.playlistId() != null) {
                Playlist playlist = playlistRepository.findById(zoneReq.playlistId())
                        .orElseThrow(() -> ApiException.badRequest("Playlist not found for zone"));
                zone.setPlaylist(playlist);
            }
            if (zoneReq.config() != null && !zoneReq.config().isNull()) {
                zone.setConfig(zoneReq.config().toString());
            }
            layout.getZones().add(zone);
        }
        // 4. persist and notify so screens using this layout re-fetch their config
        layout.setUpdatedAt(Instant.now());
        layoutRepository.saveAndFlush(layout);
        events.publishEvent(new LayoutChangedEvent(layout.getId()));
        return toDto(layout);
    }

    /** Deletes a layout unless an active schedule still shows it (409 Conflict). */
    @Transactional
    public void delete(UUID id) {
        Layout layout = getEntity(id);
        if (!scheduleRepository.findByLayoutIdAndActiveTrue(id).isEmpty()) {
            throw ApiException.conflict("This layout is used by active schedules. Delete or pause them first.");
        }
        layoutRepository.delete(layout);
    }

    private double clampPct(double v) {
        return Math.max(0, Math.min(100, v));
    }

    /** Starting zone arrangements. */
    private void applyPreset(Layout layout, String preset) {
        String p = preset == null ? "FULLSCREEN" : preset.toUpperCase();
        switch (p) {
            case "SPLIT_70_30" -> {
                addZone(layout, LayoutZone.Type.MEDIA, 0, 0, 70, 100, 1);
                addZone(layout, LayoutZone.Type.MEDIA, 70, 0, 30, 100, 2);
            }
            case "SPLIT_50_50" -> {
                addZone(layout, LayoutZone.Type.MEDIA, 0, 0, 50, 100, 1);
                addZone(layout, LayoutZone.Type.MEDIA, 50, 0, 50, 100, 2);
            }
            case "L_SHAPE" -> {
                addZone(layout, LayoutZone.Type.MEDIA, 0, 0, 75, 87.5, 1);
                LayoutZone sidebar = addZone(layout, LayoutZone.Type.WIDGET, 75, 0, 25, 87.5, 2);
                sidebar.setConfig("{\"widget\":\"CLOCK\",\"bgColor\":\"#16233F\",\"textColor\":\"#FFFFFF\"}");
                LayoutZone ticker = addZone(layout, LayoutZone.Type.TICKER, 0, 87.5, 100, 12.5, 3);
                ticker.setConfig("{\"messages\":[\"Welcome — sahi daam, poora vishwas\"],\"speed\":30,\"bgColor\":\"#F6A821\",\"textColor\":\"#16233F\"}");
            }
            default -> addZone(layout, LayoutZone.Type.MEDIA, 0, 0, 100, 100, 1);
        }
    }

    private LayoutZone addZone(Layout layout, LayoutZone.Type type, double x, double y, double w, double h, int z) {
        LayoutZone zone = new LayoutZone(layout, type);
        zone.setX(x);
        zone.setY(y);
        zone.setW(w);
        zone.setH(h);
        zone.setZ(z);
        layout.getZones().add(zone);
        return zone;
    }

    // entity -> DTO, parsing each zone's JSON config (malformed config is dropped, not fatal)
    private LayoutDtos.LayoutResponse toDto(Layout layout) {
        List<LayoutDtos.ZoneResponse> zones = layout.getZones().stream().map(z -> {
            JsonNode config = null;
            if (z.getConfig() != null) {
                try {
                    config = objectMapper.readTree(z.getConfig());
                } catch (Exception ignored) {
                }
            }
            return new LayoutDtos.ZoneResponse(
                    z.getId(), z.getType(), z.getX(), z.getY(), z.getW(), z.getH(), z.getZ(),
                    z.getPlaylist() == null ? null : z.getPlaylist().getId(),
                    z.getPlaylist() == null ? null : z.getPlaylist().getName(),
                    config);
        }).toList();
        return new LayoutDtos.LayoutResponse(
                layout.getId(), layout.getName(), layout.getOrientation(),
                zones.size(), zones,
                layout.getCreatedBy() == null ? null : layout.getCreatedBy().getFullName(),
                layout.getCreatedAt(), layout.getUpdatedAt());
    }
}
