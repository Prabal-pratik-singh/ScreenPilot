package com.screenpilot.signage.controller;

import com.screenpilot.signage.domain.MediaAsset;
import com.screenpilot.signage.dto.MediaDtos;
import com.screenpilot.signage.error.ApiException;
import com.screenpilot.signage.media.MediaService;
import com.screenpilot.signage.security.UrlSigner;
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * REST endpoints for the media library, all under /api/media. The JSON endpoints
 * require a logged-in portal user with the role named on each method. The two
 * binary endpoints (/file and /thumb) are deliberately permitAll in SecurityConfig,
 * because <img>/<video> tags and player downloads cannot attach an auth header —
 * for those, an HMAC-signed URL (?exp=&sig=) is the access control instead.
 */
@RestController
@RequestMapping("/api/media")
public class MediaController {

    private final MediaService mediaService;

    // Spring injects the service; the controller stays a thin HTTP wrapper around it.
    public MediaController(MediaService mediaService) {
        this.mediaService = mediaService;
    }

    /**
     * POST /api/media — multipart file upload. @PreAuthorize runs before the method:
     * only CONTENT_MANAGER and above may upload (through the role hierarchy, ADMIN
     * and SUPER_ADMIN inherit this right). Validation/storage happen in the service.
     */
    @PostMapping
    @PreAuthorize("hasRole('CONTENT_MANAGER')")
    public MediaDtos.MediaResponse upload(@RequestParam("file") MultipartFile file,
                                          @RequestParam(required = false) String folder,
                                          @RequestParam(required = false) String tags) {
        return mediaService.upload(file, folder, tags);
    }

    /**
     * GET /api/media — the library listing. Read-only, so the lowest role (VIEWER)
     * is enough; every query parameter is an optional filter.
     */
    @GetMapping
    @PreAuthorize("hasRole('VIEWER')")
    public List<MediaDtos.MediaResponse> list(@RequestParam(required = false) String type,
                                              @RequestParam(required = false) String tag,
                                              @RequestParam(required = false) String folder,
                                              @RequestParam(required = false) String search,
                                              @RequestParam(required = false) UUID uploaderId) {
        return mediaService.list(type, tag, folder, search, uploaderId);
    }

    // The next two read-only endpoints feed the filter dropdowns in the library UI.
    @GetMapping("/folders")
    @PreAuthorize("hasRole('VIEWER')")
    public List<String> folders() {
        return mediaService.folders();
    }

    @GetMapping("/tags")
    @PreAuthorize("hasRole('VIEWER')")
    public List<String> tags() {
        return mediaService.tags();
    }

    /** GET /api/media/{id} — details of one asset; soft-deleted assets 404 here. */
    @GetMapping("/{id}")
    @PreAuthorize("hasRole('VIEWER')")
    public MediaDtos.MediaResponse get(@PathVariable UUID id) {
        return MediaDtos.MediaResponse.from(mediaService.getActive(id));
    }

    /**
     * GET /api/media/{id}/usage — which playlists reference this asset. The UI calls
     * this to build the "used in N playlists" warning shown before a delete.
     */
    @GetMapping("/{id}/usage")
    @PreAuthorize("hasRole('VIEWER')")
    public MediaDtos.UsageResponse usage(@PathVariable UUID id) {
        return mediaService.usage(id);
    }

    /**
     * PUT /api/media/{id} — rename / re-folder / re-tag. Writes need CONTENT_MANAGER;
     * @Valid runs the bean-validation rules on the JSON body before the method runs.
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('CONTENT_MANAGER')")
    public MediaDtos.MediaResponse update(@PathVariable UUID id, @Valid @RequestBody MediaDtos.UpdateMediaRequest request) {
        return mediaService.update(id, request);
    }

    /**
     * DELETE /api/media/{id} — a soft delete: the row and the file survive, the asset
     * just leaves the listings. Also a write, so CONTENT_MANAGER and above only.
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('CONTENT_MANAGER')")
    public void delete(@PathVariable UUID id) {
        mediaService.softDelete(id);
    }

    /**
     * Streams the binary. Spring MVC converts Resource responses into 206 partial
     * content automatically when a Range header is present, so videos can seek.
     * Public: used by <img>/<video> tags and the player download manager.
     */
    @GetMapping("/{id}/file")
    public ResponseEntity<Resource> file(@PathVariable UUID id,
                                         @RequestParam(required = false) Long exp,
                                         @RequestParam(required = false) String sig) {
        // Gate first: the URL must carry a valid, unexpired HMAC signature (?exp=&sig=).
        // This stands in for the login check that SecurityConfig deliberately skips here.
        requireValidSignature(id, exp, sig);
        // Soft-deleted assets stay streamable on purpose, so players and playlists that
        // still reference them keep working; only the library listings hide them.
        MediaAsset asset = mediaService.getIncludingDeleted(id);
        Resource resource = mediaService.resourceFor(asset);
        // Tell the browser what the bytes are; fall back to the generic binary type
        // (application/octet-stream) when no MIME type was recorded at upload time.
        MediaType mediaType = asset.getMimeType() != null
                ? MediaType.parseMediaType(asset.getMimeType())
                : MediaType.APPLICATION_OCTET_STREAM;
        // "inline" asks the browser to display/play the file rather than download it;
        // safeName() sanitizes the filename so it cannot break out of this header.
        // Returning the Resource as the body is what enables the Range/206 behavior
        // described above — Spring slices the file for whatever byte range is asked.
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + safeName(asset) + "\"")
                .body(resource);
    }

    /**
     * Streams the JPEG thumbnail. Same public + signed-URL scheme as /file, because
     * thumbnails are loaded by plain <img> tags in the library grid.
     */
    @GetMapping("/{id}/thumb")
    public ResponseEntity<Resource> thumb(@PathVariable UUID id,
                                          @RequestParam(required = false) Long exp,
                                          @RequestParam(required = false) String sig) {
        requireValidSignature(id, exp, sig);
        MediaAsset asset = mediaService.getIncludingDeleted(id);
        Resource resource = mediaService.thumbFor(asset);
        // null = no thumbnail was ever generated (or its file vanished) — plain 404.
        if (resource == null) {
            return ResponseEntity.notFound().build()
                    ;
        }
        // Thumbnails never change once generated, so let browsers and proxies cache
        // them for an hour (max-age=3600) instead of re-fetching on every grid render.
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=3600")
                .body(resource);
    }

    /**
     * Header-injection guard. The asset name (user-chosen text) is placed inside the
     * quoted filename of the Content-Disposition header; a name containing a quote
     * or a CR/LF newline could otherwise close the quote early and smuggle extra
     * header lines into the response. Those characters are replaced with "_".
     */
    private String safeName(MediaAsset asset) {
        return asset.getName().replaceAll("[\\r\\n\"]", "_");
    }

    /**
     * Verifies the ?exp/?sig pair against the value "media:<id>" using the app's
     * HMAC key (see UrlSigner). The API mints these links with a short lifetime, so
     * a leaked URL dies once exp passes; missing, expired or forged signatures = 403.
     */
    private void requireValidSignature(UUID mediaId, Long exp, String sig) {
        if (!UrlSigner.instance().verify("media:" + mediaId, exp, sig)) {
            throw ApiException.forbidden("This media link is invalid or has expired");
        }
    }
}
