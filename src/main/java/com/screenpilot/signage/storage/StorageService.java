package com.screenpilot.signage.storage;

import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;

/**
 * File storage abstraction. The local-disk implementation backs the demo;
 * an S3 implementation can be swapped in without touching callers.
 */
public interface StorageService {

    /** Stores the stream and returns the storage key (relative path). */
    String store(InputStream in, String subdir, String filename) throws IOException;

    Resource loadAsResource(String key);

    long sizeOf(String key);

    void delete(String key);

    /** Absolute filesystem path for implementations that have one (used by ffmpeg). */
    java.nio.file.Path resolve(String key);
}
