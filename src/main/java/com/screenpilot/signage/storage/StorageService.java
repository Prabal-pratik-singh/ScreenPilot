package com.screenpilot.signage.storage;

import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;

/**
 * File storage abstraction. The local-disk implementation backs the demo;
 * an S3 implementation can be swapped in without touching callers.
 *
 * Why an interface: MediaService and the controllers depend only on these five
 * methods, never on WHERE the bytes physically live. Spring injects whichever
 * implementation is registered as a bean, so moving to S3 later means writing
 * one new class — nothing changes in the upload, streaming or thumbnail code.
 * This is the deliberate "seam" where cloud storage can be swapped in.
 * Files are addressed by a "key" — a relative path like "media/<uuid>.mp4" —
 * and that key is what gets saved in the database.
 */
public interface StorageService {

    /** Stores the stream and returns the storage key (relative path). */
    String store(InputStream in, String subdir, String filename) throws IOException;

    /** Opens the stored file for reading, as a Spring Resource the web layer can stream. */
    Resource loadAsResource(String key);

    /** Size of the stored file in bytes. */
    long sizeOf(String key);

    /** Removes the stored file; deleting something already gone is not an error. */
    void delete(String key);

    /** Absolute filesystem path for implementations that have one (used by ffmpeg). */
    java.nio.file.Path resolve(String key);
}
