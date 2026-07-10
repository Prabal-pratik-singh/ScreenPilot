package com.screenpilot.signage.media;

import net.coobird.thumbnailator.Thumbnails;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Extracts metadata and thumbnails. Uses ffmpeg/ffprobe when present on the
 * system; falls back to generated typed placeholders otherwise.
 */
@Service
public class MediaProbeService {

    private static final Logger log = LoggerFactory.getLogger(MediaProbeService.class);
    private static final int THUMB_WIDTH = 480;

    private final boolean ffmpegAvailable;

    public MediaProbeService() {
        this.ffmpegAvailable = checkCommand("ffmpeg") && checkCommand("ffprobe");
        log.info("ffmpeg available: {}", ffmpegAvailable);
    }

    public boolean isFfmpegAvailable() {
        return ffmpegAvailable;
    }

    private boolean checkCommand(String cmd) {
        try {
            Process p = new ProcessBuilder(cmd, "-version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    public record VideoInfo(Double durationSeconds, Integer width, Integer height) {
    }

    public Optional<VideoInfo> probeVideo(Path file) {
        if (!ffmpegAvailable) {
            return Optional.empty();
        }
        try {
            Process p = new ProcessBuilder("ffprobe", "-v", "error",
                    "-select_streams", "v:0",
                    "-show_entries", "stream=width,height:format=duration",
                    "-of", "default=noprint_wrappers=1", file.toString())
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes());
            p.waitFor(20, TimeUnit.SECONDS);
            Integer width = null, height = null;
            Double duration = null;
            for (String line : out.split("\\R")) {
                String[] kv = line.split("=", 2);
                if (kv.length != 2) continue;
                switch (kv[0].trim()) {
                    case "width" -> width = tryParseInt(kv[1]);
                    case "height" -> height = tryParseInt(kv[1]);
                    case "duration" -> duration = tryParseDouble(kv[1]);
                }
            }
            return Optional.of(new VideoInfo(duration, width, height));
        } catch (Exception e) {
            log.warn("ffprobe failed for {}: {}", file, e.getMessage());
            return Optional.empty();
        }
    }

    /** First-frame thumbnail via ffmpeg. Returns JPEG bytes or empty. */
    public Optional<byte[]> videoThumbnail(Path file) {
        if (!ffmpegAvailable) {
            return Optional.empty();
        }
        try {
            Path tmp = Files.createTempFile("thumb", ".jpg");
            try {
                Process p = new ProcessBuilder("ffmpeg", "-y", "-ss", "0.5", "-i", file.toString(),
                        "-frames:v", "1", "-vf", "scale=" + THUMB_WIDTH + ":-2", tmp.toString())
                        .redirectErrorStream(true).start();
                p.getInputStream().readAllBytes();
                p.waitFor(30, TimeUnit.SECONDS);
                if (Files.exists(tmp) && Files.size(tmp) > 0) {
                    return Optional.of(Files.readAllBytes(tmp));
                }
                return Optional.empty();
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (Exception e) {
            log.warn("ffmpeg thumbnail failed for {}: {}", file, e.getMessage());
            return Optional.empty();
        }
    }

    public record ImageInfo(byte[] thumbnail, Integer width, Integer height) {
    }

    public Optional<ImageInfo> imageThumbnail(InputStream original) {
        try {
            BufferedImage img = ImageIO.read(original);
            if (img == null) {
                return Optional.empty();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Thumbnails.of(img).width(Math.min(THUMB_WIDTH, img.getWidth())).outputFormat("jpg").toOutputStream(out);
            return Optional.of(new ImageInfo(out.toByteArray(), img.getWidth(), img.getHeight()));
        } catch (IOException e) {
            log.warn("image thumbnail failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** Renders the first PDF page as a thumbnail. */
    public Optional<byte[]> pdfThumbnail(Path file) {
        try (PDDocument doc = PDDocument.load(file.toFile())) {
            if (doc.getNumberOfPages() == 0) {
                return Optional.empty();
            }
            PDFRenderer renderer = new PDFRenderer(doc);
            BufferedImage page = renderer.renderImageWithDPI(0, 72);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Thumbnails.of(page).width(Math.min(THUMB_WIDTH, page.getWidth())).outputFormat("jpg").toOutputStream(out);
            return Optional.of(out.toByteArray());
        } catch (Exception e) {
            log.warn("pdf thumbnail failed for {}: {}", file, e.getMessage());
            return Optional.empty();
        }
    }

    /** Ink-navy placeholder card with a type glyph, when real thumbnails aren't possible. */
    public byte[] placeholderThumbnail(String label) {
        int w = THUMB_WIDTH, h = 270;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0x16, 0x23, 0x3F));
        g.fillRect(0, 0, w, h);
        // play-triangle glyph
        g.setColor(new Color(0xF6, 0xA8, 0x21));
        int cx = w / 2, cy = h / 2 - 14;
        g.fillOval(cx - 34, cy - 34, 68, 68);
        g.setColor(new Color(0x16, 0x23, 0x3F));
        Polygon tri = new Polygon(new int[]{cx - 9, cx - 9, cx + 15}, new int[]{cy - 14, cy + 14, cy}, 3);
        g.fillPolygon(tri);
        g.setColor(new Color(255, 255, 255, 200));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
        FontMetrics fm = g.getFontMetrics();
        String text = label.toUpperCase();
        g.drawString(text, (w - fm.stringWidth(text)) / 2, h - 34);
        g.dispose();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "jpg", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private Integer tryParseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private Double tryParseDouble(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception e) {
            return null;
        }
    }
}
