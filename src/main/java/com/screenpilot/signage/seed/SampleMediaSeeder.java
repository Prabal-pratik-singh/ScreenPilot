package com.screenpilot.signage.seed;

import com.screenpilot.signage.domain.MediaAsset;
import com.screenpilot.signage.domain.User;
import com.screenpilot.signage.media.MediaProbeService;
import com.screenpilot.signage.repo.MediaAssetRepository;
import com.screenpilot.signage.repo.UserRepository;
import com.screenpilot.signage.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** Seeds a few branded demo assets so playlists can be built immediately. */
@Component
@Order(2)
public class SampleMediaSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SampleMediaSeeder.class);

    private final MediaAssetRepository mediaRepository;
    private final UserRepository userRepository;
    private final StorageService storage;
    private final MediaProbeService probe;

    public SampleMediaSeeder(MediaAssetRepository mediaRepository, UserRepository userRepository,
                             StorageService storage, MediaProbeService probe) {
        this.mediaRepository = mediaRepository;
        this.userRepository = userRepository;
        this.storage = storage;
        this.probe = probe;
    }

    @Override
    @Transactional
    public void run(String... args) throws Exception {
        if (mediaRepository.count() > 0) {
            return;
        }
        User admin = userRepository.findByEmailIgnoreCase("admin@screenpilot.in").orElse(null);

        seedImage("Fresh Deals Daily", "Sabzi, fal aur zaroorat ka saaman", new Color(0x16, 0x23, 0x3F),
                new Color(0xF6, 0xA8, 0x21), "Promos", "offers", admin);
        seedImage("Diwali Dhamaka — 50% OFF", "Festive savings across the store", new Color(0x7A, 0x1F, 0x1F),
                new Color(0xF6, 0xA8, 0x21), "Promos", "diwali,offers", admin);
        seedImage("Hamara Vaada", "Sahi daam, poora vishwas", new Color(0xF6, 0xA8, 0x21),
                new Color(0x16, 0x23, 0x3F), "Brand", "brand", admin);
        seedImage("Kirana Essentials", "Atta · Chawal · Dal · Tel", new Color(0x0E, 0x4D, 0x36),
                new Color(0xFA, 0xF8, 0xF4), "Promos", "kirana", admin);

        if (probe.isFfmpegAvailable()) {
            seedDemoVideo(admin);
        }
        log.info("Seeded sample media assets (ffmpeg available: {})", probe.isFfmpegAvailable());
    }

    private void seedImage(String title, String subtitle, Color bg, Color accent, String folder, String tags,
                           User uploader) throws Exception {
        int w = 1920, h = 1080;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setPaint(new GradientPaint(0, 0, bg, w, h, bg.darker()));
        g.fillRect(0, 0, w, h);
        g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 26));
        g.fillOval(w - 700, -260, 900, 900);
        g.fillOval(-260, h - 560, 760, 760);
        g.setColor(accent);
        g.fillRoundRect(120, 340, 130, 14, 7, 7);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 110));
        drawWrapped(g, title, 120, 480, w - 260, 118);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 52));
        g.setColor(new Color(255, 255, 255, 215));
        if (bg.equals(new Color(0xF6, 0xA8, 0x21))) {
            g.setColor(new Color(0x16, 0x23, 0x3F));
        }
        g.drawString(subtitle, 122, 620);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 46));
        g.setColor(accent);
        g.drawString("screenPilot", 122, h - 110);
        g.dispose();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        byte[] bytes = out.toByteArray();

        MediaAsset asset = new MediaAsset(title, MediaAsset.Type.IMAGE);
        asset.setMimeType("image/png");
        asset.setSizeBytes(bytes.length);
        asset.setFolder(folder);
        asset.setTags(tags);
        asset.setUploadedBy(uploader);
        asset.setStoragePath(storage.store(new ByteArrayInputStream(bytes), "media", asset.getId() + ".png"));
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            Optional<MediaProbeService.ImageInfo> info = probe.imageThumbnail(in);
            if (info.isPresent()) {
                asset.setWidth(info.get().width());
                asset.setHeight(info.get().height());
                asset.setThumbPath(storage.store(new ByteArrayInputStream(info.get().thumbnail()), "thumbs",
                        asset.getId() + ".jpg"));
            }
        }
        mediaRepository.save(asset);
    }

    private void drawWrapped(Graphics2D g, String text, int x, int y, int maxWidth, int lineHeight) {
        FontMetrics fm = g.getFontMetrics();
        StringBuilder line = new StringBuilder();
        int curY = y;
        for (String word : text.split(" ")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (fm.stringWidth(candidate) > maxWidth && !line.isEmpty()) {
                g.drawString(line.toString(), x, curY);
                line = new StringBuilder(word);
                curY += lineHeight;
            } else {
                line = new StringBuilder(candidate);
            }
        }
        g.drawString(line.toString(), x, curY);
    }

    private void seedDemoVideo(User uploader) {
        try {
            Path tmp = Files.createTempFile("demo", ".mp4");
            Process p = new ProcessBuilder("ffmpeg", "-y",
                    "-f", "lavfi", "-i", "testsrc2=duration=12:size=1280x720:rate=25",
                    "-pix_fmt", "yuv420p", "-movflags", "+faststart", tmp.toString())
                    .redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0 || Files.size(tmp) == 0) {
                Files.deleteIfExists(tmp);
                return;
            }
            byte[] bytes = Files.readAllBytes(tmp);
            Files.deleteIfExists(tmp);

            MediaAsset asset = new MediaAsset("Demo Reel (test pattern)", MediaAsset.Type.VIDEO);
            asset.setMimeType("video/mp4");
            asset.setSizeBytes(bytes.length);
            asset.setFolder("Brand");
            asset.setTags("demo");
            asset.setUploadedBy(uploader);
            asset.setStoragePath(storage.store(new ByteArrayInputStream(bytes), "media", asset.getId() + ".mp4"));
            Path stored = storage.resolve(asset.getStoragePath());
            probe.probeVideo(stored).ifPresent(info -> {
                asset.setDurationSeconds(info.durationSeconds());
                asset.setWidth(info.width());
                asset.setHeight(info.height());
            });
            byte[] thumb = probe.videoThumbnail(stored).orElseGet(() -> probe.placeholderThumbnail("video"));
            asset.setThumbPath(storage.store(new ByteArrayInputStream(thumb), "thumbs", asset.getId() + ".jpg"));
            mediaRepository.save(asset);
        } catch (Exception e) {
            log.warn("Could not seed demo video: {}", e.getMessage());
        }
    }
}
