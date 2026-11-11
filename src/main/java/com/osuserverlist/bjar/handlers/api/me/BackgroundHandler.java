package com.osuserverlist.bjar.handlers.api.me;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

import org.jetbrains.annotations.NotNull;

import com.osuserverlist.bjar.handlers.api.oauth.ApiAuth;
import com.osuserverlist.bjar.models.api.ApiPagination;
import com.osuserverlist.bjar.models.database.UserEntity;
import com.osuserverlist.bjar.modules.account.DonorService;
import com.osuserverlist.bjar.modules.api.OAuthToken;
import com.osuserverlist.bjar.modules.main.WebEngine;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;
import com.osuserverlist.bjar.repos.UserRepository;

import io.javalin.http.Context;
import io.javalin.http.Handler;

/**
 * The picture drawn behind a whole profile page, uploaded rather than linked.
 *
 * <p>Same reasoning as the banner and the badge: a link would let one account
 * decide what every visitor of its profile fetches, so the upload is decoded,
 * bounded and written out again as a fresh JPEG. Nothing of the original file
 * (name, metadata, appended bytes, extra frames) survives.</p>
 *
 * <p>Unlike the banner it is not cropped to a fixed shape: a page background
 * has to cover any screen, so the picture keeps its own proportions and is only
 * scaled down to a size that is sharp on a large monitor. It is stored as JPEG
 * because it is a full screen photo that every visitor downloads, and a PNG of
 * that size would be several megabytes.</p>
 *
 * <p>A background is a supporter perk, guarded by the same donor check as the
 * banner and the badge.</p>
 */
@Host({"api.", "server", ""})
@Path("/api/v1/me/background")
@WebEngine.HttpMethod("POST")
public final class BackgroundHandler implements Handler {

    private static final int MAX_FILE_BYTES = 4 * 1024 * 1024;

    private static final int MAX_SIDE = 8192;

    private static final long MAX_PIXELS = 40_000_000L;

    private static final int MIN_SIDE = 320;

    /** Stored pictures fit in this box; smaller ones are never scaled up. */
    private static final int MAX_OUTPUT_WIDTH = 1920;

    private static final int MAX_OUTPUT_HEIGHT = 1200;

    private static final float JPEG_QUALITY = 0.85f;

    static final java.nio.file.Path BACKGROUND_DIR =
            java.nio.file.Path.of("data", "assets", "backgrounds").toAbsolutePath().normalize();

    /** Where the browser reads it back from; short enough for the 64 char column. */
    static final String PUBLIC_PREFIX = "/api/v1/background/";

    static {
        ImageIO.setUseCache(false);
    }

    @Override
    public void handle(@NotNull Context ctx) {
        OAuthToken token = ApiAuth.require(ctx);
        if (token == null || !ApiAuth.requireProfile(ctx, token)) {
            return;
        }

        UserEntity user = UserRepository.findById(token.getUserId());
        if (user == null) {
            ApiAuth.notFound(ctx, "No such user.");
            return;
        }

        if (!DonorService.isDonor(user)) {
            ctx.status(403).json(ApiPagination.error("A profile background is a supporter perk."));
            return;
        }

        String contentType = ctx.contentType();
        String mime = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT).split(";")[0].trim();
        if (!mime.equals("image/png") && !mime.equals("image/jpeg")) {
            ctx.status(415).json(Map.of("status", "Only a PNG or JPEG background is accepted."));
            return;
        }

        long declared = ctx.contentLength();
        if (declared <= 0 || declared > MAX_FILE_BYTES) {
            tooLarge(ctx);
            return;
        }

        byte[] bytes = ctx.bodyAsBytes();
        if (bytes.length == 0 || bytes.length > MAX_FILE_BYTES) {
            tooLarge(ctx);
            return;
        }

        // Renamed files and polyglot prefixes are refused before a decoder runs.
        String expectedFormat = mime.equals("image/png") ? "png" : "jpeg";
        if (!hasMagic(bytes, expectedFormat)) {
            invalid(ctx);
            return;
        }

        BufferedImage source;
        try {
            source = decode(bytes, expectedFormat);
        } catch (IOException | RuntimeException e) {
            invalid(ctx);
            return;
        }

        if (source == null) {
            invalid(ctx);
            return;
        }

        BufferedImage background = normalise(source);
        String filename = user.getId() + ".jpg";
        java.nio.file.Path target = BACKGROUND_DIR.resolve(filename).normalize();
        java.nio.file.Path temporary = null;

        if (!target.getParent().equals(BACKGROUND_DIR)) {
            ctx.status(500).json(Map.of("status", "Could not store the background."));
            return;
        }

        try {
            Files.createDirectories(BACKGROUND_DIR);
            temporary = Files.createTempFile(BACKGROUND_DIR, ".background-", ".jpg");

            writeJpeg(background, temporary);

            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }

            String path = PUBLIC_PREFIX + filename;

            user.setCustomBackground(path);
            UserRepository.save(user);

            ctx.header("Cache-Control", "no-store");

            Map<String, Object> response = ApiAuth.success();
            response.put("custom_background", path);

            ctx.json(response);

            MeSupport.logger.info("User <{}> updated their profile background from <{}>",
                    user.getId(), com.osuserverlist.bjar.modules.util.ClientIp.of(ctx));
        } catch (IOException e) {
            MeSupport.logger.error("Could not store the background of user <{}>", user.getId(), e);
            ctx.status(500).json(Map.of("status", "Could not store the background."));
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // Best effort cleanup after a failed write or move.
                }
            }
        }
    }

    /** Removes the stored picture of an account, if there is one. Best effort. */
    static void deleteFile(int userId) {
        java.nio.file.Path target = BACKGROUND_DIR.resolve(userId + ".jpg").normalize();

        if (!BACKGROUND_DIR.equals(target.getParent())) {
            return;
        }

        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            MeSupport.logger.warn("Could not delete the background file of user <{}>", userId, e);
        }
    }

    private static boolean hasMagic(byte[] bytes, String format) {
        if (format.equals("png")) {
            return bytes.length >= 8
                    && (bytes[0] & 0xff) == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4e
                    && bytes[3] == 0x47 && bytes[4] == 0x0d && bytes[5] == 0x0a
                    && bytes[6] == 0x1a && bytes[7] == 0x0a;
        }

        return bytes.length >= 4
                && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff;
    }

    private static BufferedImage decode(byte[] bytes, String format) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) {
                return null;
            }

            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return null;
            }

            ImageReader reader = readers.next();

            try {
                if (!format.equalsIgnoreCase(reader.getFormatName())) {
                    return null;
                }

                reader.setInput(input, true, true);

                // The size is read from the header first, so a decompression bomb is
                // refused before any pixel memory is allocated.
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);

                if (width < MIN_SIDE || height < MIN_SIDE
                        || width > MAX_SIDE || height > MAX_SIDE
                        || (long) width * height > MAX_PIXELS) {
                    return null;
                }

                return reader.read(0);
            } finally {
                reader.dispose();
            }
        }
    }

    /**
     * Fits the picture into the stored size and flattens it onto the page colour,
     * because JPEG has no transparency and a transparent PNG would turn black.
     */
    private static BufferedImage normalise(BufferedImage source) {
        int width = source.getWidth();
        int height = source.getHeight();

        double factor = Math.min(1.0, Math.min((double) MAX_OUTPUT_WIDTH / width,
                (double) MAX_OUTPUT_HEIGHT / height));
        int outWidth = Math.max(1, (int) Math.round(width * factor));
        int outHeight = Math.max(1, (int) Math.round(height * factor));

        BufferedImage output = new BufferedImage(outWidth, outHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = output.createGraphics();

        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);

            graphics.setColor(new Color(0x0e0e10));
            graphics.fillRect(0, 0, outWidth, outHeight);
            graphics.drawImage(source, 0, 0, outWidth, outHeight, null);
        } finally {
            graphics.dispose();
        }

        return output;
    }

    private static void writeJpeg(BufferedImage image, java.nio.file.Path target) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IOException("JPEG writer unavailable");
        }

        ImageWriter writer = writers.next();

        try (ImageOutputStream output = ImageIO.createImageOutputStream(target.toFile())) {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            param.setProgressiveMode(ImageWriteParam.MODE_DEFAULT);

            writer.setOutput(output);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
    }

    private static void tooLarge(Context ctx) {
        ctx.status(413).json(Map.of("status", "The background is too large (4 MB maximum)."));
    }

    private static void invalid(Context ctx) {
        ctx.status(400).json(Map.of("status",
                "The file is not a safe, valid PNG or JPEG image (at least 320px on each side)."));
    }
}
