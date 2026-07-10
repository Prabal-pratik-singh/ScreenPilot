package com.screenpilot.signage.media;

import com.screenpilot.signage.config.AppProperties;
import com.screenpilot.signage.domain.MediaAsset;
import com.screenpilot.signage.domain.PlaylistItem;
import com.screenpilot.signage.domain.User;
import com.screenpilot.signage.dto.MediaDtos;
import com.screenpilot.signage.error.ApiException;
import com.screenpilot.signage.repo.MediaAssetRepository;
import com.screenpilot.signage.repo.PlaylistItemRepository;
import com.screenpilot.signage.repo.UserRepository;
import com.screenpilot.signage.security.CurrentUser;
import com.screenpilot.signage.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

@Service
public class MediaService {

    private static final Logger log = LoggerFactory.getLogger(MediaService.class);

    private static final Map<String, MediaAsset.Type> EXTENSIONS = Map.of(
            "mp4", MediaAsset.Type.VIDEO,
            "webm", MediaAsset.Type.VIDEO,
            "jpg", MediaAsset.Type.IMAGE,
            "jpeg", MediaAsset.Type.IMAGE,
            "png", MediaAsset.Type.IMAGE,
            "webp", MediaAsset.Type.IMAGE,
            "pdf", MediaAsset.Type.PDF);

    private final MediaAssetRepository mediaRepository;
    private final PlaylistItemRepository playlistItemRepository;
    private final UserRepository userRepository;
    private final StorageService storage;
    private final MediaProbeService probe;
    private final AppProperties props;

    public MediaService(MediaAssetRepository mediaRepository, PlaylistItemRepository playlistItemRepository,
                        UserRepository userRepository, StorageService storage, MediaProbeService probe,
                        AppProperties props) {
        this.mediaRepository = mediaRepository;
        this.playlistItemRepository = playlistItemRepository;
        this.userRepository = userRepository;
        this.storage = storage;
        this.probe = probe;
        this.props = props;
    }

    @Transactional
    public MediaDtos.MediaResponse upload(MultipartFile file, String folder, String tags) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("No file received");
        }
        long maxBytes = props.getStorage().getMaxFileMb() * 1024 * 1024;
        if (file.getSize() > maxBytes) {
            throw ApiException.badRequest("File exceeds the " + props.getStorage().getMaxFileMb() + " MB limit");
        }
        String originalName = file.getOriginalFilename() == null ? "upload" : file.getOriginalFilename();
        String ext = extensionOf(originalName);
        MediaAsset.Type type = EXTENSIONS.get(ext);
        if (type == null) {
            throw ApiException.badRequest("Unsupported file type ." + ext + " — allowed: mp4, webm, jpg, png, webp, pdf");
        }

        MediaAsset asset = new MediaAsset(stripExtension(originalName), type);
        asset.setMimeType(file.getContentType());
        asset.setSizeBytes(file.getSize());
        asset.setFolder(blankToNull(folder));
        asset.setTags(MediaDtos.joinTags(MediaDtos.parseTags(tags)));
        User uploader = userRepository.findById(CurrentUser.get().id()).orElse(null);
        asset.setUploadedBy(uploader);

        try {
            String key = storage.store(file.getInputStream(), "media", asset.getId() + "." + ext);
            asset.setStoragePath(key);
        } catch (IOException e) {
            log.error("Failed to store upload", e);
            throw new ApiException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, "Could not store the file");
        }

        try {
            generateMetadataAndThumb(asset);
        } catch (Exception e) {
            log.warn("Thumbnail/metadata generation failed for {}: {}", asset.getName(), e.getMessage());
        }

        return MediaDtos.MediaResponse.from(mediaRepository.save(asset));
    }

    private void generateMetadataAndThumb(MediaAsset asset) throws IOException {
        Path file = storage.resolve(asset.getStoragePath());
        byte[] thumb = null;
        switch (asset.getType()) {
            case IMAGE -> {
                try (InputStream in = storage.loadAsResource(asset.getStoragePath()).getInputStream()) {
                    Optional<MediaProbeService.ImageInfo> info = probe.imageThumbnail(in);
                    if (info.isPresent()) {
                        asset.setWidth(info.get().width());
                        asset.setHeight(info.get().height());
                        thumb = info.get().thumbnail();
                    }
                }
            }
            case VIDEO -> {
                probe.probeVideo(file).ifPresent(info -> {
                    asset.setDurationSeconds(info.durationSeconds());
                    asset.setWidth(info.width());
                    asset.setHeight(info.height());
                });
                thumb = probe.videoThumbnail(file).orElseGet(() -> probe.placeholderThumbnail("video"));
            }
            case PDF -> thumb = probe.pdfThumbnail(file).orElseGet(() -> probe.placeholderThumbnail("pdf"));
        }
        if (thumb != null) {
            String thumbKey = storage.store(new ByteArrayInputStream(thumb), "thumbs", asset.getId() + ".jpg");
            asset.setThumbPath(thumbKey);
        }
    }

    @Transactional(readOnly = true)
    public List<MediaDtos.MediaResponse> list(String type, String tag, String folder, String search, UUID uploaderId) {
        return mediaRepository.findByDeletedFalseOrderByUploadedAtDesc().stream()
                .filter(m -> type == null || type.isBlank() || m.getType().name().equalsIgnoreCase(type))
                .filter(m -> tag == null || tag.isBlank() || MediaDtos.parseTags(m.getTags()).stream().anyMatch(t -> t.equalsIgnoreCase(tag)))
                .filter(m -> folder == null || folder.isBlank() || folder.equals(m.getFolder()))
                .filter(m -> uploaderId == null || (m.getUploadedBy() != null && uploaderId.equals(m.getUploadedBy().getId())))
                .filter(m -> search == null || search.isBlank() || m.getName().toLowerCase().contains(search.toLowerCase()))
                .map(MediaDtos.MediaResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public MediaAsset getActive(UUID id) {
        MediaAsset asset = mediaRepository.findById(id).orElseThrow(() -> ApiException.notFound("Media not found"));
        if (asset.isDeleted()) {
            throw ApiException.notFound("Media has been deleted");
        }
        return asset;
    }

    @Transactional(readOnly = true)
    public MediaAsset getIncludingDeleted(UUID id) {
        return mediaRepository.findById(id).orElseThrow(() -> ApiException.notFound("Media not found"));
    }

    @Transactional
    public MediaDtos.MediaResponse update(UUID id, MediaDtos.UpdateMediaRequest req) {
        MediaAsset asset = getActive(id);
        asset.setName(req.name().trim());
        asset.setFolder(blankToNull(req.folder()));
        asset.setTags(MediaDtos.joinTags(req.tags()));
        return MediaDtos.MediaResponse.from(mediaRepository.save(asset));
    }

    @Transactional(readOnly = true)
    public MediaDtos.UsageResponse usage(UUID id) {
        Map<UUID, String> playlists = new LinkedHashMap<>();
        for (PlaylistItem item : playlistItemRepository.findByMediaId(id)) {
            playlists.putIfAbsent(item.getPlaylist().getId(), item.getPlaylist().getName());
        }
        return new MediaDtos.UsageResponse(
                playlists.entrySet().stream().map(e -> new MediaDtos.PlaylistRef(e.getKey(), e.getValue())).toList());
    }

    @Transactional
    public void softDelete(UUID id) {
        MediaAsset asset = getActive(id);
        asset.setDeleted(true);
        asset.setDeletedAt(Instant.now());
        mediaRepository.save(asset);
    }

    @Transactional(readOnly = true)
    public List<String> folders() {
        return mediaRepository.findByDeletedFalseOrderByUploadedAtDesc().stream()
                .map(MediaAsset::getFolder)
                .filter(Objects::nonNull)
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<String> tags() {
        return mediaRepository.findByDeletedFalseOrderByUploadedAtDesc().stream()
                .flatMap(m -> MediaDtos.parseTags(m.getTags()).stream())
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    public org.springframework.core.io.Resource resourceFor(MediaAsset asset) {
        org.springframework.core.io.Resource res = storage.loadAsResource(asset.getStoragePath());
        if (!res.exists()) {
            throw ApiException.notFound("Media file is missing from storage");
        }
        return res;
    }

    public org.springframework.core.io.Resource thumbFor(MediaAsset asset) {
        if (asset.getThumbPath() == null) {
            return null;
        }
        org.springframework.core.io.Resource res = storage.loadAsResource(asset.getThumbPath());
        return res.exists() ? res : null;
    }

    private String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase();
    }

    private String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot <= 0 ? filename : filename.substring(0, dot);
    }

    private String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
