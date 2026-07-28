package com.screenpilot.signage.controller;

import com.screenpilot.signage.dto.PlaylistDtos;
import com.screenpilot.signage.service.PlaylistService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** REST CRUD for playlists: VIEWER can read, CONTENT_MANAGER can change. */
@RestController
@RequestMapping("/api/playlists")
public class PlaylistController {

    private final PlaylistService playlistService;

    public PlaylistController(PlaylistService playlistService) {
        this.playlistService = playlistService;
    }

    // GET /api/playlists — all playlists (without items); VIEWER and up
    @GetMapping
    @PreAuthorize("hasRole('VIEWER')")
    public List<PlaylistDtos.PlaylistResponse> list() {
        return playlistService.list();
    }

    // GET /api/playlists/{id} — one playlist with its items; VIEWER and up
    @GetMapping("/{id}")
    @PreAuthorize("hasRole('VIEWER')")
    public PlaylistDtos.PlaylistResponse get(@PathVariable UUID id) {
        return playlistService.get(id);
    }

    // POST /api/playlists — create an empty playlist; CONTENT_MANAGER and up
    @PostMapping
    @PreAuthorize("hasRole('CONTENT_MANAGER')")
    public PlaylistDtos.PlaylistResponse create(@Valid @RequestBody PlaylistDtos.SavePlaylistRequest request) {
        return playlistService.create(request);
    }

    // PUT /api/playlists/{id} — rename / edit description; CONTENT_MANAGER and up
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('CONTENT_MANAGER')")
    public PlaylistDtos.PlaylistResponse update(@PathVariable UUID id, @Valid @RequestBody PlaylistDtos.SavePlaylistRequest request) {
        return playlistService.update(id, request);
    }

    // PUT /api/playlists/{id}/items — replace the ordered item list; CONTENT_MANAGER and up
    @PutMapping("/{id}/items")
    @PreAuthorize("hasRole('CONTENT_MANAGER')")
    public PlaylistDtos.PlaylistResponse saveItems(@PathVariable UUID id, @Valid @RequestBody PlaylistDtos.SaveItemsRequest request) {
        return playlistService.saveItems(id, request);
    }

    // DELETE /api/playlists/{id} — delete a playlist; CONTENT_MANAGER and up
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('CONTENT_MANAGER')")
    public void delete(@PathVariable UUID id) {
        playlistService.delete(id);
    }
}
