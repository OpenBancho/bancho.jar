package com.osuserverlist.bjar.handlers.api.me;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import java.util.Map;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

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
 * The cover picture at the top of a profile, uploaded rather than linked.
 *
 * <p>Same reasoning as the badge picture: a link would leave the banner at the
 * mercy of whatever host it points at, and would let one account decide what
 * every visitor of its profile fetches. The upload is decoded, bounded and
 * written out again as a fresh PNG, so nothing of the original file (name,
 * metadata, appended bytes, extra frames) survives.</p>
 *
 * <p>A banner is a supporter perk, so the same donor check as the badge guards
 * it, and the stored path is written straight onto the account.</p>
 */
@Host({"api.", "server", ""})
@Path("/api/v1/me/banner")
@WebEngine.HttpMethod("POST")
public final class BannerHandler implements Handler {

    /** A cover is a large picture, so it gets more room than a badge does. */
    private static final int MAX_FILE_BYTES = 4 * 1024 * 1024;

    private static final int MAX_SIDE = 6000;

    private static final long MAX_PIXELS = 24_000_000L;

    /** Wider than the profile card ever gets, so it still looks sharp on 2x screens. */
    private static final int MAX_OUTPUT_WIDTH = 1600;

    /** Covers are wide strips: every picture is stored four times as wide as it is tall. */
    private static final int ASPECT_WIDTH = 4;

    private static final int ASPECT_HEIGHT = 1;

    private static final int MIN_WIDTH = 200;

    private static final int MIN_HEIGHT = 50;

    static final java.nio.file.Path BANNER_DIR =
            java.nio.file.Path.of("data", "assets", "banners").toAbsolutePath().normalize();

    /** Where the browser reads it back from; short enough for the 64 char column. */
    static final String PUBLIC_PREFIX = "/api/v1/banner/";

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
            ctx.status(403).json(ApiPagination.error("A profile banner is a supporter perk."));
            return;
        }

        String contentType = ctx.contentType();
        if (contentType == null || !contentType.equalsIgnoreCase("image/png")) {
            ctx.status(415).json(Map.of("status", "Only a PNG banner is accepted."));
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
        if (bytes.length < 8
                || (bytes[0] & 0xff) != 0x89 || bytes[1] != 0x50 || bytes[2] != 0x4e
                || bytes[3] != 0x47 || bytes[4] != 0x0d || bytes[5] != 0x0a
                || bytes[6] != 0x1a || bytes[7] != 0x0a) {
            invalid(ctx);
            return;
        }

        BufferedImage source;
        try {
            source = decodePng(bytes);
        } catch (IOException | RuntimeException e) {
            invalid(ctx);
            return;
        }

        if (source == null || source.getWidth() < MIN_WIDTH || source.getHeight() < MIN_HEIGHT
                || source.getWidth() > MAX_SIDE || source.getHeight() > MAX_SIDE
                || (long) source.getWidth() * source.getHeight() > MAX_PIXELS) {
            invalid(ctx);
            return;
        }

        BufferedImage banner = normalise(source);
        String filename = user.getId() + ".png";
        java.nio.file.Path target = BANNER_DIR.resolve(filename).normalize();
        java.nio.file.Path temporary = null;

        if (!target.getParent().equals(BANNER_DIR)) {
            ctx.status(500).json(Map.of("status", "Could not store the banner."));
            return;
        }

        try {
            Files.createDirectories(BANNER_DIR);
            temporary = Files.createTempFile(BANNER_DIR, ".banner-", ".png");

            if (!ImageIO.write(banner, "png", temporary.toFile())) {
                throw new IOException("PNG writer unavailable");
            }

            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }

            String path = PUBLIC_PREFIX + filename;

            user.setCustomBanner(path);
            UserRepository.save(user);

            ctx.header("Cache-Control", "no-store");

            Map<String, Object> response = ApiAuth.success();
            response.put("custom_banner", path);

            ctx.json(response);

            MeSupport.logger.info("User <{}> updated their profile banner from <{}>",
                    user.getId(), ctx.ip());
        } catch (IOException e) {
            MeSupport.logger.error("Could not store the banner of user <{}>", user.getId(), e);
            ctx.status(500).json(Map.of("status", "Could not store the banner."));
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

    private static BufferedImage decodePng(byte[] bytes) throws IOException {
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
                if (!"png".equalsIgnoreCase(reader.getFormatName())) {
                    return null;
                }

                reader.setInput(input, true, true);

                int width = reader.getWidth(0);
                int height = reader.getHeight(0);

                if (width < MIN_WIDTH || height < MIN_HEIGHT
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
     * Crops the picture to the strip a cover is drawn in, then scales it down.
     *
     * <p>The slot at the top of a profile is a wide 4:1 band. Storing the file in
     * that shape lets the page show it without letterboxing or squashing it,
     * whatever the player uploaded, and keeps one account from pushing the rest of
     * the page down with a very tall image.</p>
     */
    private static BufferedImage normalise(BufferedImage source) {
        int width = source.getWidth();
        int height = source.getHeight();

        // The largest centred 4:1 rectangle that fits inside the upload.
        int cropWidth = Math.min(width, height * ASPECT_WIDTH / ASPECT_HEIGHT);
        int cropHeight = Math.max(1, cropWidth * ASPECT_HEIGHT / ASPECT_WIDTH);
        int x = (width - cropWidth) / 2;
        int y = (height - cropHeight) / 2;

        double factor = cropWidth > MAX_OUTPUT_WIDTH ? (double) MAX_OUTPUT_WIDTH / cropWidth : 1.0;
        int outWidth = Math.max(1, (int) Math.round(cropWidth * factor));
        int outHeight = Math.max(1, (int) Math.round(cropHeight * factor));

        BufferedImage output = new BufferedImage(outWidth, outHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = output.createGraphics();

        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);

            graphics.drawImage(source, 0, 0, outWidth, outHeight,
                    x, y, x + cropWidth, y + cropHeight, null);
        } finally {
            graphics.dispose();
        }

        return output;
    }

    private static void tooLarge(Context ctx) {
        ctx.status(413).json(Map.of("status", "The banner is too large (4 MB maximum)."));
    }

    private static void invalid(Context ctx) {
        ctx.status(400).json(Map.of("status", "The file is not a safe, valid PNG image."));
    }
}
