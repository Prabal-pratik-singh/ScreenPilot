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

/**
 * All business rules for the media library: uploading files, listing and filtering,
 * renaming, soft-deleting, and handing the stored bytes back to the controller.
 * Marked @Service so Spring creates one shared instance and injects it into
 * MediaController — the controller stays a thin HTTP layer, the real logic lives here.
 */
@Service
public class MediaService {

    // Logger for this class; records storage failures and probe warnings server-side.
    private static final Logger log = LoggerFactory.getLogger(MediaService.class);

    // The upload allow-list. Only these file extensions are accepted, and each one maps
    // directly to a media type (VIDEO / IMAGE / PDF). The type is decided from the
    // extension — NOT from the Content-Type header the browser sends — because the
    // client controls that MIME header and could claim "image/png" for anything.
    private static final Map<String, MediaAsset.Type> EXTENSIONS = Map.of(
            "mp4", MediaAsset.Type.VIDEO,
            "webm", MediaAsset.Type.VIDEO,
            "jpg", MediaAsset.Type.IMAGE,
            "jpeg", MediaAsset.Type.IMAGE,
            "png", MediaAsset.Type.IMAGE,
            "webp", MediaAsset.Type.IMAGE,
            "pdf", MediaAsset.Type.PDF);

    // Collaborators this service needs: database repositories, the file-storage
    // abstraction, the thumbnail/metadata prober, and the app's configured limits.
    private final MediaAssetRepository mediaRepository;
    private final PlaylistItemRepository playlistItemRepository;
    private final UserRepository userRepository;
    private final StorageService storage;
    private final MediaProbeService probe;
    private final AppProperties props;

    // Constructor injection: Spring looks at these parameter types and passes in the
    // matching beans automatically when it builds this service at startup.
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

    /**
     * Handles one file upload end to end: validate size and type, build the database
     * row, write the bytes to storage under a UUID filename, then try to extract a
     * thumbnail and metadata. @Transactional wraps it all in one database transaction,
     * so if anything throws, the half-created row is rolled back automatically.
     */
    @Transactional
    public MediaDtos.MediaResponse upload(MultipartFile file, String folder, String tags) {
        // Step 1: reject empty submissions (no file part at all, or a zero-byte file).
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("No file received");
        }
        // Step 2: size check. The limit is configured in megabytes (app.storage.max-file-mb),
        // so multiply it out to bytes before comparing with the upload's real size.
        long maxBytes = props.getStorage().getMaxFileMb() * 1024 * 1024;
        if (file.getSize() > maxBytes) {
            throw ApiException.badRequest("File exceeds the " + props.getStorage().getMaxFileMb() + " MB limit");
        }
        // Step 3: type check via the extension allow-list. The browser may omit the
        // filename entirely, so fall back to "upload". An extension missing from the
        // EXTENSIONS map means an unsupported type — reject before doing any work.
        String originalName = file.getOriginalFilename() == null ? "upload" : file.getOriginalFilename();
        String ext = extensionOf(originalName);
        MediaAsset.Type type = EXTENSIONS.get(ext);
        if (type == null) {
            throw ApiException.badRequest("Unsupported file type ." + ext + " — allowed: mp4, webm, jpg, png, webp, pdf");
        }

        // Step 4: build the entity. The display name is the original filename without
        // its extension; the entity itself generates the asset's random UUID id.
        MediaAsset asset = new MediaAsset(stripExtension(originalName), type);
        // The client-sent MIME type is stored only as descriptive metadata (reused later
        // for the Content-Type header when streaming) — it never decided the type above.
        asset.setMimeType(file.getContentType());
        asset.setSizeBytes(file.getSize());
        // Optional organizing fields: a blank folder becomes null, and the raw tags text
        // is parsed then re-joined so it is stored in one normalized comma-separated form.
        asset.setFolder(blankToNull(folder));
        asset.setTags(MediaDtos.joinTags(MediaDtos.parseTags(tags)));
        // Record who uploaded it. CurrentUser reads the logged-in portal user from the
        // security context; orElse(null) tolerates the rare case that the account is gone.
        User uploader = userRepository.findById(CurrentUser.get().id()).orElse(null);
        asset.setUploadedBy(uploader);

        // Step 5: write the bytes to storage as "media/<uuid>.<ext>". The on-disk name is
        // the asset's UUID — the user's filename NEVER touches the disk, so a malicious
        // name (like "..\..\evil.exe" or one full of strange characters) cannot influence
        // where, or as what, the file lands. The returned key is saved on the entity so
        // the file can be located again for streaming and thumbnails.
        try {
            String key = storage.store(file.getInputStream(), "media", asset.getId() + "." + ext);
            asset.setStoragePath(key);
        } catch (IOException e) {
            // A failed disk write is the server's problem, not the client's: keep the full
            // stack trace in our log, but answer with only a generic 500 message.
            log.error("Failed to store upload", e);
            throw new ApiException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, "Could not store the file");
        }

        // Step 6: thumbnails and metadata are nice-to-have extras. If ffmpeg is missing
        // or the file is unusual, log a warning and carry on — a failed probe must never
        // fail the upload itself, so this catch deliberately swallows every exception.
        try {
            generateMetadataAndThumb(asset);
        } catch (Exception e) {
            log.warn("Thumbnail/metadata generation failed for {}: {}", asset.getName(), e.getMessage());
        }

        // Step 7: persist the finished row and convert it into the JSON response shape.
        return MediaDtos.MediaResponse.from(mediaRepository.save(asset));
    }

    /**
     * Best-effort extraction of dimensions/duration plus a JPEG preview, branching on
     * the media type. Whatever thumbnail comes out is stored as "thumbs/<uuid>.jpg".
     */
    private void generateMetadataAndThumb(MediaAsset asset) throws IOException {
        // Resolve the storage key to a real filesystem path — ffmpeg/ffprobe run as
        // external programs and need an actual file path, not a Java stream.
        Path file = storage.resolve(asset.getStoragePath());
        byte[] thumb = null;
        switch (asset.getType()) {
            // Images: decoded in pure Java. try-with-resources guarantees the stream is
            // closed; on success we learn the pixel size and get a shrunken preview.
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
            // Videos: ffprobe reports duration and frame size (an empty Optional just
            // means we skip the metadata), then ffmpeg grabs one frame as the preview;
            // if that fails we fall back to a generated "VIDEO" placeholder card.
            case VIDEO -> {
                probe.probeVideo(file).ifPresent(info -> {
                    asset.setDurationSeconds(info.durationSeconds());
                    asset.setWidth(info.width());
                    asset.setHeight(info.height());
                });
                thumb = probe.videoThumbnail(file).orElseGet(() -> probe.placeholderThumbnail("video"));
            }
            // PDFs: render page 1 as the preview, or fall back to a "PDF" placeholder card.
            case PDF -> thumb = probe.pdfThumbnail(file).orElseGet(() -> probe.placeholderThumbnail("pdf"));
        }
        // If any branch produced preview bytes, store them beside the media under
        // "thumbs/<uuid>.jpg" and remember the key so the /thumb endpoint can serve it.
        if (thumb != null) {
            String thumbKey = storage.store(new ByteArrayInputStream(thumb), "thumbs", asset.getId() + ".jpg");
            asset.setThumbPath(thumbKey);
        }
    }

    /**
     * Media library listing with optional filters. Every parameter is optional — a
     * null or blank value means "do not filter on this". readOnly = true declares the
     * transaction will never write, which lets the persistence layer optimize.
     */
    @Transactional(readOnly = true)
    public List<MediaDtos.MediaResponse> list(String type, String tag, String folder, String search, UUID uploaderId) {
        // Start from all NON-deleted assets, newest first, then apply each filter in
        // turn: type (exact, case-insensitive), tag (any of the asset's tags matches),
        // folder (exact), uploader (by id), and search (name substring, case-insensitive).
        return mediaRepository.findByDeletedFalseOrderByUploadedAtDesc().stream()
                .filter(m -> type == null || type.isBlank() || m.getType().name().equalsIgnoreCase(type))
                .filter(m -> tag == null || tag.isBlank() || MediaDtos.parseTags(m.getTags()).stream().anyMatch(t -> t.equalsIgnoreCase(tag)))
                .filter(m -> folder == null || folder.isBlank() || folder.equals(m.getFolder()))
                .filter(m -> uploaderId == null || (m.getUploadedBy() != null && uploaderId.equals(m.getUploadedBy().getId())))
                .filter(m -> search == null || search.isBlank() || m.getName().toLowerCase().contains(search.toLowerCase()))
                // finally, convert each surviving entity into the API response shape
                .map(MediaDtos.MediaResponse::from)
                .toList();
    }

    /**
     * Loads an asset for normal API use: an unknown id AND a soft-deleted asset both
     * come back as 404, so deleted media is invisible to everyday operations.
     */
    @Transactional(readOnly = true)
    public MediaAsset getActive(UUID id) {
        MediaAsset asset = mediaRepository.findById(id).orElseThrow(() -> ApiException.notFound("Media not found"));
        if (asset.isDeleted()) {
            throw ApiException.notFound("Media has been deleted");
        }
        return asset;
    }

    /**
     * Loads an asset even if it was soft-deleted. Used by the binary /file and /thumb
     * endpoints so players and playlists that still reference the asset keep working
     * after a delete — only the library listings hide it.
     */
    @Transactional(readOnly = true)
    public MediaAsset getIncludingDeleted(UUID id) {
        return mediaRepository.findById(id).orElseThrow(() -> ApiException.notFound("Media not found"));
    }

    /**
     * Edits the descriptive fields only (name, folder, tags) — the stored file itself
     * never changes. Going through getActive means deleted assets cannot be edited.
     */
    @Transactional
    public MediaDtos.MediaResponse update(UUID id, MediaDtos.UpdateMediaRequest req) {
        MediaAsset asset = getActive(id);
        asset.setName(req.name().trim());
        asset.setFolder(blankToNull(req.folder()));
        asset.setTags(MediaDtos.joinTags(req.tags()));
        return MediaDtos.MediaResponse.from(mediaRepository.save(asset));
    }

    /**
     * Answers "where is this media used?" by finding every playlist that references it.
     * The UI calls this before a delete so it can warn "used in N playlists" instead
     * of letting content silently vanish from screens.
     */
    @Transactional(readOnly = true)
    public MediaDtos.UsageResponse usage(UUID id) {
        // A playlist can contain the same asset several times; putIfAbsent de-duplicates
        // down to one entry per playlist, and LinkedHashMap keeps a stable order.
        Map<UUID, String> playlists = new LinkedHashMap<>();
        for (PlaylistItem item : playlistItemRepository.findByMediaId(id)) {
            playlists.putIfAbsent(item.getPlaylist().getId(), item.getPlaylist().getName());
        }
        // Convert the id→name map into the response's list of small playlist references.
        return new MediaDtos.UsageResponse(
                playlists.entrySet().stream().map(e -> new MediaDtos.PlaylistRef(e.getKey(), e.getValue())).toList());
    }

    /**
     * Soft delete: flip a flag and record when, instead of removing the row or the
     * file. History (playback logs, playlist items) keeps valid references, players
     * can still stream the bytes, and the action stays reversible — the asset simply
     * disappears from listings because those queries filter on deleted = false.
     */
    @Transactional
    public void softDelete(UUID id) {
        MediaAsset asset = getActive(id);
        asset.setDeleted(true);
        asset.setDeletedAt(Instant.now());
        mediaRepository.save(asset);
    }

    /**
     * Every distinct folder name in use across live assets, alphabetized — this feeds
     * the folder filter dropdown in the UI. Assets without a folder are skipped.
     */
    @Transactional(readOnly = true)
    public List<String> folders() {
        return mediaRepository.findByDeletedFalseOrderByUploadedAtDesc().stream()
                .map(MediaAsset::getFolder)
                .filter(Objects::nonNull)
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    /**
     * Every distinct tag across live assets, alphabetized — feeds the tag filter.
     * flatMap first expands each asset's comma-separated tags into individual entries.
     */
    @Transactional(readOnly = true)
    public List<String> tags() {
        return mediaRepository.findByDeletedFalseOrderByUploadedAtDesc().stream()
                .flatMap(m -> MediaDtos.parseTags(m.getTags()).stream())
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    /**
     * Opens the stored file for streaming. The database row can exist while the file
     * is gone (disk wiped, folder moved) — checking exists() turns that into a clean
     * 404 instead of an error halfway through sending the response.
     */
    public org.springframework.core.io.Resource resourceFor(MediaAsset asset) {
        org.springframework.core.io.Resource res = storage.loadAsResource(asset.getStoragePath());
        if (!res.exists()) {
            throw ApiException.notFound("Media file is missing from storage");
        }
        return res;
    }

    /**
     * The thumbnail as a readable resource, or null when none was ever generated or
     * its file is missing — the controller turns null into a 404 response.
     */
    public org.springframework.core.io.Resource thumbFor(MediaAsset asset) {
        if (asset.getThumbPath() == null) {
            return null;
        }
        org.springframework.core.io.Resource res = storage.loadAsResource(asset.getThumbPath());
        return res.exists() ? res : null;
    }

    // Text after the last dot, lowercased ("Movie.MP4" -> "mp4"); "" when there is no dot.
    private String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase();
    }

    // Filename without its extension. The "dot <= 0" check keeps dot-files like ".env"
    // whole instead of stripping them down to an empty name.
    private String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot <= 0 ? filename : filename.substring(0, dot);
    }

    // Normalizes optional text inputs: blank or whitespace-only becomes null (stored
    // as SQL NULL), anything else is trimmed.
    private String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
