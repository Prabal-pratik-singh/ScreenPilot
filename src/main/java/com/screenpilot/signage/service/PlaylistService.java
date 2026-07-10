package com.screenpilot.signage.service;

import com.screenpilot.signage.domain.MediaAsset;
import com.screenpilot.signage.domain.Playlist;
import com.screenpilot.signage.domain.PlaylistItem;
import com.screenpilot.signage.domain.User;
import com.screenpilot.signage.dto.PlaylistDtos;
import com.screenpilot.signage.error.ApiException;
import com.screenpilot.signage.repo.MediaAssetRepository;
import com.screenpilot.signage.repo.PlaylistRepository;
import com.screenpilot.signage.repo.UserRepository;
import com.screenpilot.signage.security.CurrentUser;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class PlaylistService {

    /** Fired after a playlist's content changes so schedule/push machinery can react. */
    public record PlaylistChangedEvent(UUID playlistId) {
    }

    private final PlaylistRepository playlistRepository;
    private final MediaAssetRepository mediaRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher events;

    public PlaylistService(PlaylistRepository playlistRepository, MediaAssetRepository mediaRepository,
                           UserRepository userRepository, ApplicationEventPublisher events) {
        this.playlistRepository = playlistRepository;
        this.mediaRepository = mediaRepository;
        this.userRepository = userRepository;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public List<PlaylistDtos.PlaylistResponse> list() {
        return playlistRepository.findAllByOrderByUpdatedAtDesc().stream()
                .map(p -> PlaylistDtos.PlaylistResponse.from(p, false))
                .toList();
    }

    @Transactional(readOnly = true)
    public PlaylistDtos.PlaylistResponse get(UUID id) {
        return PlaylistDtos.PlaylistResponse.from(getEntity(id), true);
    }

    @Transactional(readOnly = true)
    public Playlist getEntity(UUID id) {
        return playlistRepository.findWithItemsById(id)
                .orElseThrow(() -> ApiException.notFound("Playlist not found"));
    }

    @Transactional
    public PlaylistDtos.PlaylistResponse create(PlaylistDtos.SavePlaylistRequest req) {
        User creator = userRepository.findById(CurrentUser.get().id()).orElse(null);
        Playlist playlist = playlistRepository.save(new Playlist(req.name().trim(), req.description(), creator));
        return PlaylistDtos.PlaylistResponse.from(playlist, true);
    }

    @Transactional
    public PlaylistDtos.PlaylistResponse update(UUID id, PlaylistDtos.SavePlaylistRequest req) {
        Playlist playlist = getEntity(id);
        playlist.setName(req.name().trim());
        playlist.setDescription(req.description());
        playlistRepository.save(playlist);
        return PlaylistDtos.PlaylistResponse.from(playlist, true);
    }

    @Transactional
    public PlaylistDtos.PlaylistResponse saveItems(UUID id, PlaylistDtos.SaveItemsRequest req) {
        Playlist playlist = getEntity(id);
        playlist.getItems().clear();
        int position = 0;
        for (PlaylistDtos.SaveItemRequest itemReq : req.items()) {
            PlaylistItem item = new PlaylistItem(playlist, position++);
            item.setItemType(itemReq.itemType());
            switch (itemReq.itemType()) {
                case MEDIA -> {
                    if (itemReq.mediaId() == null) {
                        throw ApiException.badRequest("mediaId is required for MEDIA items");
                    }
                    MediaAsset media = mediaRepository.findById(itemReq.mediaId())
                            .orElseThrow(() -> ApiException.badRequest("Media asset not found: " + itemReq.mediaId()));
                    if (media.isDeleted()) {
                        throw ApiException.badRequest("\"" + media.getName() + "\" has been deleted and cannot be added");
                    }
                    item.setMedia(media);
                    item.setTitle(media.getName());
                    if (media.getType() != MediaAsset.Type.VIDEO) {
                        item.setDurationSeconds(itemReq.durationSeconds() != null && itemReq.durationSeconds() > 0
                                ? itemReq.durationSeconds()
                                : PlaylistDtos.DEFAULT_STATIC_SECONDS);
                    }
                }
                case URL, YOUTUBE -> {
                    if (itemReq.url() == null || itemReq.url().isBlank()) {
                        throw ApiException.badRequest("url is required for external items");
                    }
                    item.setUrl(itemReq.url().trim());
                    item.setTitle(itemReq.title() == null || itemReq.title().isBlank()
                            ? (itemReq.itemType() == PlaylistItem.ItemType.YOUTUBE ? "YouTube video" : "Web page")
                            : itemReq.title().trim());
                    item.setDurationSeconds(itemReq.durationSeconds() != null && itemReq.durationSeconds() > 0
                            ? itemReq.durationSeconds()
                            : PlaylistDtos.DEFAULT_EXTERNAL_SECONDS);
                }
            }
            playlist.getItems().add(item);
        }
        playlist.setUpdatedAt(Instant.now());
        playlistRepository.saveAndFlush(playlist);
        events.publishEvent(new PlaylistChangedEvent(playlist.getId()));
        return PlaylistDtos.PlaylistResponse.from(playlist, true);
    }

    @Transactional
    public void delete(UUID id) {
        Playlist playlist = getEntity(id);
        playlistRepository.delete(playlist);
    }
}
