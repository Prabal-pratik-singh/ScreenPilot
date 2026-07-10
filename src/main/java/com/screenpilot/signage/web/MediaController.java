package com.screenpilot.signage.web;

import com.screenpilot.signage.domain.MediaAsset;
import com.screenpilot.signage.dto.MediaDtos;
import com.screenpilot.signage.media.MediaService;
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

@RestController
@RequestMapping("/api/media")
public class MediaController {

    private final MediaService mediaService;

    public MediaController(MediaService mediaService) {
        this.mediaService = mediaService;
    }

    @PostMapping
    @PreAuthorize("hasRole('CONTENT_MANAGER')")
    public MediaDtos.MediaResponse upload(@RequestParam("file") MultipartFile file,
                                          @RequestParam(required = false) String folder,
                                          @RequestParam(required = false) String tags) {
        return mediaService.upload(file, folder, tags);
    }

    @GetMapping
    @PreAuthorize("hasRole('VIEWER')")
    public List<MediaDtos.MediaResponse> list(@RequestParam(required = false) String type,
                                              @RequestParam(required = false) String tag,
                                              @RequestParam(required = false) String folder,
                                              @RequestParam(required = false) String search,
                                              @RequestParam(required = false) UUID uploaderId) {
        return mediaService.list(type, tag, folder, search, uploaderId);
    }

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

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('VIEWER')")
    public MediaDtos.MediaResponse get(@PathVariable UUID id) {
        return MediaDtos.MediaResponse.from(mediaService.getActive(id));
    }

    @GetMapping("/{id}/usage")
    @PreAuthorize("hasRole('VIEWER')")
    public MediaDtos.UsageResponse usage(@PathVariable UUID id) {
        return mediaService.usage(id);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('CONTENT_MANAGER')")
    public MediaDtos.MediaResponse update(@PathVariable UUID id, @Valid @RequestBody MediaDtos.UpdateMediaRequest request) {
        return mediaService.update(id, request);
    }

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
    public ResponseEntity<Resource> file(@PathVariable UUID id) {
        MediaAsset asset = mediaService.getIncludingDeleted(id);
        Resource resource = mediaService.resourceFor(asset);
        MediaType mediaType = asset.getMimeType() != null
                ? MediaType.parseMediaType(asset.getMimeType())
                : MediaType.APPLICATION_OCTET_STREAM;
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + safeName(asset) + "\"")
                .body(resource);
    }

    @GetMapping("/{id}/thumb")
    public ResponseEntity<Resource> thumb(@PathVariable UUID id) {
        MediaAsset asset = mediaService.getIncludingDeleted(id);
        Resource resource = mediaService.thumbFor(asset);
        if (resource == null) {
            return ResponseEntity.notFound().build()
                    ;
        }
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=3600")
                .body(resource);
    }

    private String safeName(MediaAsset asset) {
        return asset.getName().replaceAll("[\\r\\n\"]", "_");
    }
}
