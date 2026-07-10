package com.screenpilot.signage.web;

import com.screenpilot.signage.dto.PlaylistDtos;
import com.screenpilot.signage.service.PlaylistService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/playlists")
public class PlaylistController {

    private final PlaylistService playlistService;

    public PlaylistController(PlaylistService playlistService) {
        this.playlistService = playlistService;
    }

    @GetMapping
    @PreAuthorize("hasRole('VIEWER')")
    public List<PlaylistDtos.PlaylistResponse> list() {
        return playlistService.list();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('VIEWER')")
    public PlaylistDtos.PlaylistResponse get(@PathVariable UUID id) {
        return playlistService.get(id);
    }

    @PostMapping
    @PreAuthorize("hasRole('CONTENT_MANAGER')")
    public PlaylistDtos.PlaylistResponse create(@Valid @RequestBody PlaylistDtos.SavePlaylistRequest request) {
        return playlistService.create(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('CONTENT_MANAGER')")
    public PlaylistDtos.PlaylistResponse update(@PathVariable UUID id, @Valid @RequestBody PlaylistDtos.SavePlaylistRequest request) {
        return playlistService.update(id, request);
    }

    @PutMapping("/{id}/items")
    @PreAuthorize("hasRole('CONTENT_MANAGER')")
    public PlaylistDtos.PlaylistResponse saveItems(@PathVariable UUID id, @Valid @RequestBody PlaylistDtos.SaveItemsRequest request) {
        return playlistService.saveItems(id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('CONTENT_MANAGER')")
    public void delete(@PathVariable UUID id) {
        playlistService.delete(id);
    }
}
