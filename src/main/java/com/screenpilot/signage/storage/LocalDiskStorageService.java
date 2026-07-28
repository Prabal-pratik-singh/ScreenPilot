package com.screenpilot.signage.storage;

import com.screenpilot.signage.config.AppProperties;
import org.springframework.core.io.PathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * {@link StorageService} implementation that keeps files in a folder on the local disk
 * (configured by app.storage.dir). Marked {@code @Service} so Spring creates it as a bean
 * and injects it wherever StorageService is required. Every resolved path is checked to
 * stay inside the root folder, which blocks path-traversal tricks like "../../etc".
 */
@Service
public class LocalDiskStorageService implements StorageService {

    /** Absolute, normalized upload root; every stored file must live under it. */
    private final Path root;

    /**
     * Reads the configured folder (app.storage.dir), converts it into an absolute,
     * cleaned-up path, and creates it if missing — so the very first upload never
     * fails on a directory that does not exist yet.
     */
    public LocalDiskStorageService(AppProperties props) throws IOException {
        // toAbsolutePath() + normalize() produce one canonical form of the root, which
        // is what makes the startsWith(root) containment checks below reliable.
        this.root = Paths.get(props.getStorage().getDir()).toAbsolutePath().normalize();
        Files.createDirectories(root);
    }

    /** Copies the stream to "<root>/<subdir>/<filename>" and returns the relative key. */
    @Override
    public String store(InputStream in, String subdir, String filename) throws IOException {
        // Build the target folder (e.g. root/media) and make sure it exists.
        // normalize() collapses any "." or ".." segments hiding in the inputs.
        Path dir = root.resolve(subdir).normalize();
        Files.createDirectories(dir);
        Path target = dir.resolve(filename).normalize();
        // Path-traversal guard: after normalizing, the final target must still sit
        // inside the root. A crafted name like "..\..\Windows\evil" would escape it
        // and is refused here before a single byte is written.
        if (!target.startsWith(root)) {
            throw new IOException("Invalid storage path");
        }
        // Stream the bytes to disk. REPLACE_EXISTING makes re-storing the same key an
        // intentional overwrite (e.g. regenerating a thumbnail) instead of an error.
        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        // Return the key relative to the root, with forward slashes so the exact same
        // database value works on both Windows and Linux.
        return root.relativize(target).toString().replace('\\', '/');
    }

    // Wraps the absolute path in a Spring PathResource so controllers can stream it;
    // resolve() re-runs the traversal guard on every read.
    @Override
    public Resource loadAsResource(String key) {
        return new PathResource(resolve(key));
    }

    // File size in bytes; a missing or unreadable file reports 0 rather than throwing.
    @Override
    public long sizeOf(String key) {
        try {
            return Files.size(resolve(key));
        } catch (IOException e) {
            return 0;
        }
    }

    // Best-effort delete: deleteIfExists tolerates already-missing files, and an
    // IOException is swallowed on purpose because a failed disk cleanup should not
    // fail the API operation that triggered it.
    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException ignored) {
        }
    }

    /** Turns a storage key back into an absolute path, re-checking containment. */
    @Override
    public Path resolve(String key) {
        // normalize() collapses "." and ".." so the containment check cannot be fooled.
        Path p = root.resolve(key).normalize();
        // Keys normally come from our own database, but verify defensively anyway:
        // anything resolving outside the root folder is rejected before disk access.
        if (!p.startsWith(root)) {
            throw new IllegalArgumentException("Invalid storage key");
        }
        return p;
    }
}
