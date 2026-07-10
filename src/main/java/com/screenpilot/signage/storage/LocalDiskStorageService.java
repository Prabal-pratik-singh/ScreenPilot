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

@Service
public class LocalDiskStorageService implements StorageService {

    private final Path root;

    public LocalDiskStorageService(AppProperties props) throws IOException {
        this.root = Paths.get(props.getStorage().getDir()).toAbsolutePath().normalize();
        Files.createDirectories(root);
    }

    @Override
    public String store(InputStream in, String subdir, String filename) throws IOException {
        Path dir = root.resolve(subdir).normalize();
        Files.createDirectories(dir);
        Path target = dir.resolve(filename).normalize();
        if (!target.startsWith(root)) {
            throw new IOException("Invalid storage path");
        }
        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        return root.relativize(target).toString().replace('\\', '/');
    }

    @Override
    public Resource loadAsResource(String key) {
        return new PathResource(resolve(key));
    }

    @Override
    public long sizeOf(String key) {
        try {
            return Files.size(resolve(key));
        } catch (IOException e) {
            return 0;
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException ignored) {
        }
    }

    @Override
    public Path resolve(String key) {
        Path p = root.resolve(key).normalize();
        if (!p.startsWith(root)) {
            throw new IllegalArgumentException("Invalid storage key");
        }
        return p;
    }
}
