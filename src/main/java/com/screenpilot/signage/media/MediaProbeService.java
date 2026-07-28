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
    // Every preview is shrunk to 480px wide — sharp enough for grid cards, cheap to store.
    private static final int THUMB_WIDTH = 480;

    // Decided once at startup and cached: are the ffmpeg tools usable on this machine?
    private final boolean ffmpegAvailable;

    /**
     * Availability probe, run once when Spring constructs this bean: both ffmpeg
     * (grabs thumbnail frames) and ffprobe (reads metadata) must respond for video
     * features to switch on. Checking once here beats re-testing on every upload,
     * and if either tool is missing the app degrades to placeholders instead of
     * erroring on each video.
     */
    public MediaProbeService() {
        this.ffmpegAvailable = checkCommand("ffmpeg") && checkCommand("ffprobe");
        log.info("ffmpeg available: {}", ffmpegAvailable);
    }

    /** True when both tools answered at startup — lets other code branch or report on it. */
    public boolean isFfmpegAvailable() {
        return ffmpegAvailable;
    }

    /** Runs "<cmd> -version" to test whether the tool exists and is runnable on the PATH. */
    private boolean checkCommand(String cmd) {
        try {
            // redirectErrorStream merges stderr into stdout, leaving ONE pipe to read.
            Process p = new ProcessBuilder(cmd, "-version").redirectErrorStream(true).start();
            // Drain everything the tool prints BEFORE waiting on it. If nobody reads
            // the pipe and the OS buffer fills up, the child process blocks on its own
            // output and waitFor() would deadlock — draining stdout first prevents that.
            p.getInputStream().readAllBytes();
            // Available = it finished within 5 seconds AND reported exit code 0.
            return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            // Any failure (not installed, no permission, interrupted) means "not available".
            return false;
        }
    }

    // Small carrier for what ffprobe found. Boxed types (Double/Integer, not double/int)
    // because any value can be null when the probe only partially succeeds.
    public record VideoInfo(Double durationSeconds, Integer width, Integer height) {
    }

    /**
     * Asks ffprobe for a video's width, height and duration. Returns empty instead of
     * throwing on any problem — metadata is optional decoration, never a hard need.
     */
    public Optional<VideoInfo> probeVideo(Path file) {
        // Without the tools there is nothing to ask; skip instantly.
        if (!ffmpegAvailable) {
            return Optional.empty();
        }
        try {
            // The ffprobe command, piece by piece:
            //   -v error                  → print only real errors, no version banner
            //   -select_streams v:0       → inspect the first video stream only
            //   -show_entries stream=width,height:format=duration
            //                             → output just these three values
            //   -of default=noprint_wrappers=1 → as bare "key=value" lines, no headers
            // stderr is merged into stdout so one read below captures everything.
            Process p = new ProcessBuilder("ffprobe", "-v", "error",
                    "-select_streams", "v:0",
                    "-show_entries", "stream=width,height:format=duration",
                    "-of", "default=noprint_wrappers=1", file.toString())
                    .redirectErrorStream(true).start();
            // Read the full output first (which also avoids the pipe-buffer deadlock),
            // then give the process up to 20 seconds to exit.
            String out = new String(p.getInputStream().readAllBytes());
            p.waitFor(20, TimeUnit.SECONDS);
            // Parse lines like "width=1920": split each line ("\R" = any line break
            // style) on the first '=', keep the three keys we care about, skip the rest.
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
            // Any of the three may still be null — callers cope with partial info.
            return Optional.of(new VideoInfo(duration, width, height));
        } catch (Exception e) {
            // A broken or odd video must not break the upload: warn and report "no info".
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
            // ffmpeg writes its result to a file, not to a pipe — create a temp .jpg for it.
            Path tmp = Files.createTempFile("thumb", ".jpg");
            try {
                // The ffmpeg command, piece by piece:
                //   -y              → overwrite the temp file without asking
                //   -ss 0.5         → seek half a second in before grabbing: frame 0 is
                //                     often pure black (fade-in), 0.5s shows real content
                //   -i <file>       → the input video
                //   -frames:v 1     → capture exactly one frame
                //   -vf scale=480:-2 → resize to 480px wide; "-2" means "pick the height
                //                     that keeps the aspect ratio, rounded to an even
                //                     number" (many encoders require even dimensions)
                Process p = new ProcessBuilder("ffmpeg", "-y", "-ss", "0.5", "-i", file.toString(),
                        "-frames:v", "1", "-vf", "scale=" + THUMB_WIDTH + ":-2", tmp.toString())
                        .redirectErrorStream(true).start();
                // Drain ffmpeg's chatty output so a full pipe buffer can't deadlock it,
                // then allow up to 30 seconds (large videos can be slow to open).
                p.getInputStream().readAllBytes();
                p.waitFor(30, TimeUnit.SECONDS);
                // Judge success by the RESULT rather than the exit code: a real
                // thumbnail means the temp file now exists and is non-empty.
                if (Files.exists(tmp) && Files.size(tmp) > 0) {
                    return Optional.of(Files.readAllBytes(tmp));
                }
                return Optional.empty();
            } finally {
                // Always remove the temp file, on success and failure alike.
                Files.deleteIfExists(tmp);
            }
        } catch (Exception e) {
            // Never let a thumbnail problem bubble up — warn, return empty, and the
            // caller falls back to the placeholder card.
            log.warn("ffmpeg thumbnail failed for {}: {}", file, e.getMessage());
            return Optional.empty();
        }
    }

    // Thumbnail bytes plus the ORIGINAL image's pixel dimensions.
    public record ImageInfo(byte[] thumbnail, Integer width, Integer height) {
    }

    /**
     * Decodes an uploaded image in memory and produces a JPEG preview plus the
     * original dimensions. Pure Java — no ffmpeg involved for images.
     */
    public Optional<ImageInfo> imageThumbnail(InputStream original) {
        try {
            // ImageIO returns null (not an exception) when the bytes are not a
            // decodable image — treat that the same as any other failure.
            BufferedImage img = ImageIO.read(original);
            if (img == null) {
                return Optional.empty();
            }
            // Thumbnailator shrinks to at most 480px wide — Math.min stops it from
            // UPscaling images that are already smaller — and re-encodes as JPEG.
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Thumbnails.of(img).width(Math.min(THUMB_WIDTH, img.getWidth())).outputFormat("jpg").toOutputStream(out);
            return Optional.of(new ImageInfo(out.toByteArray(), img.getWidth(), img.getHeight()));
        } catch (IOException e) {
            // Same philosophy as the rest of this class: warn, return empty, move on.
            log.warn("image thumbnail failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** Renders the first PDF page as a thumbnail. */
    public Optional<byte[]> pdfThumbnail(Path file) {
        // PDFBox opens the document; try-with-resources guarantees it is closed again.
        try (PDDocument doc = PDDocument.load(file.toFile())) {
            // A zero-page PDF has nothing to draw.
            if (doc.getNumberOfPages() == 0) {
                return Optional.empty();
            }
            // Draw page index 0 (the first page) into a bitmap at 72 DPI — screen
            // resolution, plenty for a small preview and quick to render.
            PDFRenderer renderer = new PDFRenderer(doc);
            BufferedImage page = renderer.renderImageWithDPI(0, 72);
            // Shrink to the standard width and encode as JPEG, same as image thumbs.
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Thumbnails.of(page).width(Math.min(THUMB_WIDTH, page.getWidth())).outputFormat("jpg").toOutputStream(out);
            return Optional.of(out.toByteArray());
        } catch (Exception e) {
            // PDFs can be malformed or password-protected; warn + empty keeps the
            // upload alive and lets the caller substitute the placeholder card.
            log.warn("pdf thumbnail failed for {}: {}", file, e.getMessage());
            return Optional.empty();
        }
    }

    /** Ink-navy placeholder card with a type glyph, when real thumbnails aren't possible. */
    public byte[] placeholderThumbnail(String label) {
        // 480x270 = a 16:9 canvas, the same shape a real video thumbnail would have.
        int w = THUMB_WIDTH, h = 270;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        // Draw with Java2D; antialiasing smooths the circle and triangle edges.
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        // Fill the whole card with the dark ink-navy background (#16233F).
        g.setColor(new Color(0x16, 0x23, 0x3F));
        g.fillRect(0, 0, w, h);
        // play-triangle glyph
        // An amber circle (#F6A821) centered slightly above the middle...
        g.setColor(new Color(0xF6, 0xA8, 0x21));
        int cx = w / 2, cy = h / 2 - 14;
        g.fillOval(cx - 34, cy - 34, 68, 68);
        // ...with a navy right-pointing triangle drawn inside it, like a play button.
        g.setColor(new Color(0x16, 0x23, 0x3F));
        Polygon tri = new Polygon(new int[]{cx - 9, cx - 9, cx + 15}, new int[]{cy - 14, cy + 14, cy}, 3);
        g.fillPolygon(tri);
        // The type label ("VIDEO" / "PDF") in semi-transparent white near the bottom;
        // FontMetrics measures the text width so it can be centered horizontally.
        g.setColor(new Color(255, 255, 255, 200));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
        FontMetrics fm = g.getFontMetrics();
        String text = label.toUpperCase();
        g.drawString(text, (w - fm.stringWidth(text)) / 2, h - 34);
        // Release the native drawing resources now that the image is complete.
        g.dispose();
        try {
            // Encode the finished canvas as JPEG bytes, all in memory.
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "jpg", out);
            return out.toByteArray();
        } catch (IOException e) {
            // Writing JPEG to memory essentially cannot fail; if it somehow does, this
            // last-resort fallback has nothing left to fall back to — fail loudly.
            throw new IllegalStateException(e);
        }
    }

    // Lenient number parsing: ffprobe sometimes prints "N/A" or other non-numbers,
    // so a value that will not parse becomes null instead of throwing.
    private Integer tryParseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return null;
        }
    }

    // Same idea for decimal values like the duration ("12.480000").
    private Double tryParseDouble(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception e) {
            return null;
        }
    }
}
